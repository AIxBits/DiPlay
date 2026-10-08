package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.cert.Certificate

class LogUploadTest {
    @Test fun usbAndPairingIdentifiersAreRemovedIncludingOldQueuedReports() {
        val root=temp()
        try {
            val file=File(root,"old.txt")
            file.writeText("usbTransport=0123456789abcdef0123456789abcdef\nhostId=private-host\nsystemBuid=private-buid\nNCM sentBytes=512 interface=4")
            val connection=Connection()
            LogUploadTransport(config()) { connection }.submit(PendingLog(file,""),"0.26","Android")
            val text=connection.sent.toString("UTF-8")
            assertFalse(text.contains("0123456789abcdef"))
            assertFalse(text.contains("private-host"));assertFalse(text.contains("private-buid"))
            assertTrue(text.contains("sentBytes=512 interface=4"))
            assertTrue(text.contains("usbTransport=[redacted]"))
        } finally { root.deleteRecursively() }
    }
    private fun temp()=java.nio.file.Files.createTempDirectory("diplay-upload-test").toFile()
    private class Connection(val status:Int=201,val body:String="{\"ok\":true,\"logId\":\"LOG-SYNTHETIC\"}"):HttpsURLConnection(URL("https://example.test/api/v1/logs")) {
        val sent=ByteArrayOutputStream();var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getCipherSuite()="TLS_TEST"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream():OutputStream=sent
        override fun getInputStream():InputStream=ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode()=status
    }
    private fun config()=LogUploadConfig(URL("https://example.test/api/v1/logs"),"test-app","a".repeat(48))
    @Test fun sanitizeCredentialsIdentifiersAndPathsBeforeOfflinePersistence() {
        val root=temp()
        try {
            val queue=LogUploadQueue(root)
            val record=queue.enqueue("OK video=3\nX-Upload-Token: secret\nssid=private\n192.168.1.2\naa:bb:cc:dd:ee:ff\n/Users/person/WeChat/file\n-----BEGIN PRIVATE KEY-----\nprivatekeybytes\n-----END PRIVATE KEY-----","USB无画面")
            val text=record.file.readText()
            for(secret in listOf("secret","privatekeybytes","ssid=private","192.168.1.2","aa:bb:cc:dd:ee:ff","/Users/person")) assertFalse(text.contains(secret))
            assertTrue(text.contains("video=3"));assertTrue(text.contains("USB无画面"))
            assertEquals(1,LogUploadQueue(root).pending().size) // Survives queue/app reconstruction.
        } finally { root.deleteRecursively() }
    }
    @Test fun exactContractAndReceiptAreUsedWithoutRedirects() {
        val root=temp()
        try {
            val queue=LogUploadQueue(root);val log=queue.enqueue("test", "USB问题\r\nX-Evil: bad")
            val connection=Connection()
            val receipt=LogUploadTransport(config()) { connection }.submit(log,"0.25","Android 4.3 / SDK18")
            assertEquals("LOG-SYNTHETIC",receipt)
            assertEquals("POST",connection.requestMethod)
            assertEquals("text/plain; charset=utf-8",connection.getRequestProperty("Content-Type"))
            assertEquals("test-app",connection.getRequestProperty("X-App-Id"))
            assertEquals("a".repeat(48),connection.getRequestProperty("X-Upload-Token"))
            assertTrue(connection.getRequestProperty("X-Description").all { it.toInt()<128 })
            assertFalse(connection.getRequestProperty("X-Description").contains('\n'))
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.sent.toString("UTF-8").contains("USB问题"))
            assertTrue(connection.disconnected)
            queue.accepted(log);assertTrue(queue.pending().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun failuresAndMalformedSuccessKeepPendingLog() {
        val root=temp()
        try {
            val queue=LogUploadQueue(root);val log=queue.enqueue("test", "")
            for(c in listOf(Connection(401),Connection(403),Connection(500),Connection(302),Connection(201,"{}"),Connection(201,"{\"ok\":false,\"logId\":\"LOG-BAD\"}"))) {
                try { LogUploadTransport(config()) { c }.submit(log,"0.25","Android");fail("Invalid response accepted") } catch(_:Exception) {}
                assertTrue(c.disconnected);assertTrue(log.file.exists());assertEquals(1,queue.pending().size)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun offlineQueueIsBoundedAndNeverOverwritesExistingLogs() {
        val root=temp()
        try {
            val queue=LogUploadQueue(root)
            repeat(5) { queue.enqueue("log $it", "") }
            try { queue.enqueue("new", "");fail("Queue overflow") } catch(_:IllegalArgumentException) {}
            assertEquals(5,queue.pending().size)
            queue.clear();assertTrue(queue.pending().isEmpty())
            try { queue.enqueue("x".repeat(2*1024*1024+1), "");fail("Oversized log accepted") } catch(_:IllegalArgumentException) {}
            assertTrue(queue.pending().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun uploadConfigurationRejectsPlaintextAndCredentialUrls() {
        for(url in listOf("http://example.test/api", "https://secret@example.test/api", "https://example.test/api?token=secret")) {
            try { LogUploadConfig(URL(url),"test-app","a".repeat(48));fail("Unsafe upload URL accepted") } catch(_:IllegalArgumentException) {}
        }
        assertFalse(config().toString().contains("a".repeat(48)))
    }
    @Test fun ipv6ListenerPreservesConcreteAddressAndScope() {
        val bytes=java.net.InetAddress.getByName("fe80::2").address
        val scoped=java.net.Inet6Address.getByAddress(null,bytes,7)
        assertSame(scoped,LegacyBindAddress.any(scoped))
        assertEquals(7,(LegacyBindAddress.any(scoped) as java.net.Inet6Address).scopeId)
        assertEquals("0.0.0.0",LegacyBindAddress.any(java.net.InetAddress.getByName("127.0.0.1")).hostAddress)
        try { LegacyBindAddress.any(java.net.InetAddress.getByName("::"));fail("IPv6 wildcard accepted") } catch(_:IllegalArgumentException) {}
    }
}
