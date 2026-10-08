package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class NcmPortCountersTest {
    private fun frame(port:Int,seq:Int)=ByteArray(60).apply {
        this[0]=0x60;this[5]=20;this[6]=17;this[42]=(port shr 8).toByte();this[43]=port.toByte();this[45]=20
        this[48]=0x80.toByte();this[50]=(seq shr 8).toByte();this[51]=seq.toByte()
    }
    @Test fun countsEveryValidUdpDatagramOnRegisteredPortEvenWithoutRtpHeader() {
        val probe=LegacyAudioIngress();LegacyAudioIngress.register(3201,probe)
        try {
            val raw=frame(3201,1);raw[48]=1
            LegacyAudioIngress.observe(raw,0,raw.size,false);LegacyAudioIngress.observe(raw,0,raw.size,true)
            assertTrue(probe.summary().contains("ncmDatagrams=1 tunDatagrams=1"));assertTrue(probe.summary().startsWith("ncm=0"))
        }finally{LegacyAudioIngress.unregister(3201,probe)}
    }
    @Test fun classifiesUdpAfterExtensionHeaderAndTracksFirstLastAndLateSequences() {
        val probe=LegacyAudioIngress();LegacyAudioIngress.register(3202,probe)
        try {
            val original=frame(3202,1);val extended=ByteArray(68)
            original.copyInto(extended,0,0,40);original.copyInto(extended,48,40,60)
            extended[5]=28;extended[6]=0;extended[40]=17
            LegacyAudioIngress.observe(extended,0,extended.size,false)
            for(seq in listOf(3,2,4)){val p=frame(3202,seq);LegacyAudioIngress.observe(p,0,p.size,false)}
            val value=probe.summary()
            for(field in listOf("port=3202","ncmDatagrams=4","ncmFirst=1","ncmLast=4","ncmLateOrDuplicate=1","ncmGaps=1"))assertTrue(value,value.contains(field))
        }finally{LegacyAudioIngress.unregister(3202,probe)}
    }
    @Test fun nonAtomicFragmentsAndTruncatedExtensionsAreNotMisclassified() {
        val p=frame(3201,1);p[6]=44;p[43]=1
        assertNull(Ipv6TransportView.parse(p,0,p.size))
        p[6]=0;p[41]=100
        assertNull(Ipv6TransportView.parse(p,0,p.size))
    }
    @Test fun malformedUdpLengthIsExplicitlyDistinguishedFromMissingUsbTraffic() {
        val probe=LegacyAudioIngress();LegacyAudioIngress.register(3203,probe)
        try {
            val p=frame(3203,1);p[45]=50
            LegacyAudioIngress.observe(p,0,p.size,false);LegacyAudioIngress.observe(p,0,p.size,true)
            assertTrue(probe.summary().contains("ncmMalformedDatagrams=1"));assertTrue(probe.summary().contains("ncmDatagrams=0"))
        }finally{LegacyAudioIngress.unregister(3203,probe)}
    }
}
