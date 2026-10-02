package local.airuize.receiver

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.Collections

class LegacyIpv6TransportTest {
    @Test(timeout = 6000) fun setupOpensTimingEventAndKeepaliveOnIpv6Stack() {
        assertEquals("false", System.getProperty("java.net.preferIPv4Stack"))
        val loopback = InetAddress.getByName("::1")
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        ServerSocket(0, 50, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { client ->
                client.soTimeout = 2000
                val session = AirPlaySession(listener.accept(),
                    AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "740.11", AirPlayDisplayConfig(800, 480)),
                    AirPlayIdentity.generate(), PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onControlFailure(stage: String, error: Throwable) { failures.add(error) }
                    }, object : AirPlayMediaHandler {})
                try {
                    session.start()
                    val body = BplistCodec.encode(mapOf("timingPort" to 0, "keepAliveLowPower" to true))
                    val head = "SETUP rtsp://legacy/ RTSP/1.0\r\nCSeq: 3\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n"
                    client.getOutputStream().write(head.toByteArray(Charsets.US_ASCII) + body)
                    client.getOutputStream().flush()
                    val input = BufferedInputStream(client.getInputStream())
                    val header = ByteArrayOutputStream()
                    var suffix = ""
                    while (!suffix.endsWith("\r\n\r\n")) {
                        val byte = input.read(); assertTrue("Unexpected EOF", byte >= 0)
                        header.write(byte); suffix = (suffix + byte.toChar()).takeLast(4)
                        assertTrue(header.size() < 16384)
                    }
                    val text = header.toString("US-ASCII")
                    assertTrue("SETUP failed: $text", text.startsWith("RTSP/1.0 200 "))
                    val length = Regex("(?i)Content-Length: ([0-9]+)").find(text)!!.groupValues[1].toInt()
                    val responseBody = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(responseBody, offset, length - offset); assertTrue(count > 0); offset += count }
                    val response = BplistCodec.decode(responseBody) as Map<*, *>
                    for (key in listOf("timingPort", "eventPort", "keepAlivePort")) assertTrue((response[key] as Number).toInt() in 1..65535)
                    DatagramSocket(InetSocketAddress(loopback, 0)).use { udp ->
                        udp.soTimeout = 2000
                        val request = ByteArray(32).apply { this[0] = 0x80.toByte(); this[1] = 210.toByte(); this[3] = 7; for (i in 24..31) this[i] = i.toByte() }
                        udp.send(DatagramPacket(request, request.size, loopback, (response["timingPort"] as Number).toInt()))
                        val packet = DatagramPacket(ByteArray(64), 64); udp.receive(packet)
                        assertEquals(32, packet.length); assertEquals(211, packet.data[1].toInt() and 255)
                        assertArrayEquals(request.copyOfRange(24, 32), packet.data.copyOfRange(8, 16))
                        udp.send(DatagramPacket(byteArrayOf(1), 1, loopback, (response["keepAlivePort"] as Number).toInt()))
                    }
                    assertTrue(failures.isEmpty())
                } finally { session.close() }
            }
        }
    }
    @Test(timeout = 3000) fun mediaListenersOpenIpv6Ports() {
        val loopback = InetAddress.getByName("::1")
        AudioStream(ByteArray(32), bindAddress = loopback).use { audio ->
            val ports = audio.listen(object : AudioStream.Listener {})
            assertTrue(ports.first > 0); assertTrue(ports.second > 0); assertNotEquals(ports.first, ports.second)
        }
        ScreenStream(ByteArray(32), bindAddress = loopback).use { screen ->
            val port = screen.listen(object : ScreenStream.Listener {})
            Socket(loopback, port).use { assertTrue(it.isConnected) }
        }
        IapTunnel(ByteArray(32), bindAddress = loopback).use { tunnel ->
            val port = tunnel.listen(object : IapTunnel.Listener {})
            Socket(loopback, port).use { assertTrue(it.isConnected) }
        }
    }
}
