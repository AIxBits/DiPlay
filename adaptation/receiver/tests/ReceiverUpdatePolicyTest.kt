package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class ReceiverUpdatePolicyTest {
    @Test fun onlyOffersNewerApi18Package() {
        assertTrue(ReceiverUpdatePolicy.compatible("local.airuize.diplaylegacy",18,31,30))
        assertFalse(ReceiverUpdatePolicy.compatible("local.airuize.diplaylegacy",21,31,30))
        assertFalse(ReceiverUpdatePolicy.compatible("another.package",18,31,30))
        assertFalse(ReceiverUpdatePolicy.compatible("local.airuize.diplaylegacy",18,30,30))
    }
    @Test fun acceptsHttpsAndRejectsPlaintextAndEmbeddedCredentials() {
        assertEquals("https",ReceiverUpdatePolicy.url("https://example.com/android43.json").protocol)
        for(url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/#secret")) {
            try {ReceiverUpdatePolicy.url(url);fail("invalid URL accepted")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun microphoneNeverBindsToPhoneAddress() {
        val phone=java.net.InetAddress.getByName("fe80::3")
        val local=java.net.InetAddress.getByName("fe80::2")
        assertEquals(local,LegacyMicrophoneBind.local(phone,local))
        try {LegacyMicrophoneBind.local(phone,phone);fail("bound to phone")} catch(_:IllegalArgumentException) {}
        assertTrue(LegacyMicrophoneBind.local(java.net.InetAddress.getByName("127.0.0.1"),null).isAnyLocalAddress)
    }
    @Test fun acceptsReleaseWrapperAndRelativeDownloadUrlWithoutInventingMinSdk() {
        val value=ReceiverUpdatePolicy.release(mapOf("ok" to true,"release" to mapOf(
            "packageName" to "local.airuize.diplaylegacy","versionCode" to 32,"versionName" to "0.32",
            "downloadUrl" to "/downloads/android/app.apk","releaseNotes" to "Fixes")),ReceiverUpdatePolicy.DEFAULT_ENDPOINT)
        assertNull(value.minSdk)
        assertEquals("https://updates.aixbits.com/downloads/android/app.apk",value.downloadUrl)
        assertEquals("Fixes",value.notes)
    }
    @Test fun acceptsSnakeCaseMetadataAndRejectsWrongPackageNewAndroidOrServiceError() {
        val fields=mapOf("package_name" to "local.airuize.diplaylegacy","version_code" to 32,
            "min_sdk" to 18,"download_url" to "https://updates.aixbits.com/downloads/android/app.apk")
        assertEquals(18,ReceiverUpdatePolicy.release(mapOf("data" to fields),ReceiverUpdatePolicy.DEFAULT_ENDPOINT).minSdk)
        for(data in listOf(fields+("package_name" to "com.example.app"),fields+("min_sdk" to 21),mapOf("ok" to false,"error" to "Not found"))) {
            try {ReceiverUpdatePolicy.release(data,ReceiverUpdatePolicy.DEFAULT_ENDPOINT);fail("invalid release accepted")} catch(_:Exception) {}
        }
    }
}
