package local.airuize.receiver
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DirectAudioRouterTest {
    private val local=InetAddress.getByName("fe80::2")
    private val peer=InetAddress.getByName("fe80::1")
    private fun frame(port:Int,payload:ByteArray):ByteArray {
        val size=8+payload.size;val packet=ByteArray(40+size)
        packet[0]=0x60;packet[4]=(size ushr 8).toByte();packet[5]=size.toByte();packet[6]=17;packet[7]=64
        peer.address.copyInto(packet,8);local.address.copyInto(packet,24)
        packet[40]=0x12;packet[41]=0x34;packet[42]=(port ushr 8).toByte();packet[43]=port.toByte()
        packet[44]=(size ushr 8).toByte();packet[45]=size.toByte();payload.copyInto(packet,48)
        var sum=size+17
        for(i in 8 until packet.size step 2) {
            sum+=(packet[i].toInt() and 255) shl 8
            if(i+1<packet.size)sum+=packet[i+1].toInt() and 255
        }
        while(sum ushr 16!=0)sum=(sum and 65535)+(sum ushr 16)
        var checksum=sum xor 65535;if(checksum==0)checksum=65535
        packet[46]=(checksum ushr 8).toByte();packet[47]=checksum.toByte()
        return packet
    }
    @Test fun routesOnlyMatchingSessionPeerAndPortAndUnregistersOnClose() {
        val session=DirectAudioRouter.open(local,true);val packets=ArrayList<ByteArray>()
        try {
            val binding=DirectAudioRouter.bind(local,peer,50000){packets.add(it)}!!
            val payload=byteArrayOf(1,2,3);val packet=frame(50000,payload)
            assertTrue(DirectAudioRouter.validChecksum(packet,0,40,11))
            assertTrue(session.route(packet,0,packet.size));assertArrayEquals(payload,packets.single())
            val ethernet=ByteArray(14)+packet;assertTrue(session.route(ethernet,14,packet.size));assertEquals(2,packets.size)
            val foreign=packet.copyOf();foreign[23]=9;assertFalse(session.route(foreign,0,foreign.size))
            val other=frame(50001,payload);assertFalse(session.route(other,0,other.size))
            binding.close();assertFalse(session.route(packet,0,packet.size))
        } finally {session.close()}
        assertNull(DirectAudioRouter.bind(local,peer,50000){})
    }
    @Test fun invalidChecksumAndLengthAreConsumedWithoutPayloadDelivery() {
        val session=DirectAudioRouter.open(local,true);var count=0
        try {
            val binding=DirectAudioRouter.bind(local,peer,50000){count++}!!
            val packet=frame(50000,ByteArray(4));packet[48]=1
            assertTrue(session.route(packet,0,packet.size));assertEquals(1,binding.badChecksum.get())
            val malformed=frame(50000,ByteArray(4));malformed[45]=100
            assertTrue(session.route(malformed,0,malformed.size));assertEquals(1,binding.invalidLength.get());assertEquals(0,count)
        }finally{session.close()}
    }
    @Test fun disabledModeAndUnsupportedHeadersUseTunFallback() {
        DirectAudioRouter.open(local,false).use {session ->
            assertNull(DirectAudioRouter.bind(local,peer,50000){})
            val packet=frame(50000,byteArrayOf(1));assertFalse(session.route(packet,0,packet.size))
        }
        DirectAudioRouter.open(local,true).use {session ->
            DirectAudioRouter.bind(local,peer,50000){}
            val packet=frame(50000,byteArrayOf(1));packet[6]=44
            assertFalse(session.route(packet,0,packet.size))
            assertFalse(session.route(packet,0,39))
        }
    }
    @Test fun directQueueIsBoundedAndOldSessionCannotFeedNewReceiver() {
        val socket=DatagramSocket(0,InetAddress.getByName("127.0.0.1"));val ingress=LegacyAudioIngress()
        val session=DirectAudioRouter.open(local,true)
        val receiver=LegacyAudioReceiver(socket,ingress,2,local,peer)
        try {
            repeat(20){val packet=frame(socket.localPort,byteArrayOf(it.toByte()));assertTrue(session.route(packet,0,packet.size))}
            assertEquals(20,receiver.received.get());assertEquals(18,receiver.dropped.get())
            assertEquals(0,receiver.next()!![0].toInt());assertEquals(1,receiver.next()!![0].toInt())
            assertTrue(ingress.summary().contains("direct=20"));assertTrue(ingress.summary().contains("udp=0"))
            session.close();val packet=frame(socket.localPort,byteArrayOf(21));assertFalse(session.route(packet,0,packet.size))
        }finally{receiver.close();session.close()}
    }
    @Test(timeout=5000) fun directAudioStillAuthenticatesAndSocketFallbackRemainsAvailable() {
        val key=ByteArray(32);val ok=CountDownLatch(2);val bad=CountDownLatch(1)
        val session=DirectAudioRouter.open(local,true)
        val audio=AudioStream(key,100,{},InetAddress.getByName("127.0.0.1"),local,peer)
        try {
            val port=audio.listen(object:AudioStream.Listener {
                override fun onRtp(rtp:ByteArray,sample:Int){ok.countDown()}
                override fun onPacket(wire:ByteArray,rtp:ByteArray?,sample:Int?,error:Throwable?){if(error!=null)bad.countDown()}
            }).first
            fun wire(seq:Int):ByteArray {
                val header=ByteArray(12);header[0]=0x80.toByte();header[3]=seq.toByte()
                val nonce=AirPlayCrypto.nonce64(seq.toLong())
                return header+AirPlayCrypto.chachaSeal(key,nonce,ByteArray(16),header.copyOfRange(4,12))+nonce.copyOfRange(4,12)
            }
            val packet=frame(port,wire(1));assertTrue(session.route(packet,0,packet.size))
            DatagramSocket().use {sender->val bytes=wire(2);sender.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),port))}
            assertTrue(ok.await(2,TimeUnit.SECONDS))
            val corrupt=wire(3);corrupt[12]=(corrupt[12].toInt() xor 1).toByte();val invalid=frame(port,corrupt)
            assertTrue(session.route(invalid,0,invalid.size));assertTrue(bad.await(1,TimeUnit.SECONDS))
        }finally{audio.close();session.close()}
    }
}
