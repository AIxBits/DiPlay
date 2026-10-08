package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
class KernelAudioDiagnosticsTest {
    @Test fun snmpIncludesOnlyRelevantNumericCounters() {
        assertEquals(mapOf("Udp6InErrors" to 3L,"Udp6RcvbufErrors" to 2L),KernelAudioDiagnostics.snmp("Udp6InErrors 3\nUdp6RcvbufErrors 2\nOtherCounter 99\nUdp6InDatagrams invalid"))
    }
    @Test fun socketSelectsOwnUidAndActivePortWithoutExportingAddresses() {
        val text="""sl local_address remote_address st tx_queue:rx_queue tr tm retr uid timeout inode ref pointer drops
0: 00000000000000000000000000000000:C350 00000000000000000000000000000000:0000 07 00000000:00000400 00:00000000 00000000 1234 0 555 2 00000000 7
1: 00000000000000000000000000000000:C351 00000000000000000000000000000000:0000 07 00000000:00000100 00:00000000 00000000 9999 0 556 2 00000000 50"""
        assertEquals(mapOf("port50000Drops" to 7L,"port50000RxQueueBytes" to 1024L),KernelAudioDiagnostics.socket(text,setOf(50000,50001),1234))
        assertTrue(KernelAudioDiagnostics.socket(text,setOf(42),1234).isEmpty())
    }
    @Test fun softnetAddsHexDropsAcrossCpusAndReportsMalformedAsUnknown() {
        assertEquals(18L,KernelAudioDiagnostics.softnet("00000001 00000010 00000000\n00000002 00000002 00000000"))
        assertNull(KernelAudioDiagnostics.softnet("bad"))
        assertNull(KernelAudioDiagnostics.softnet(""))
    }
}
