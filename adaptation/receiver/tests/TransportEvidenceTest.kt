package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class TransportEvidenceTest {
    @Test fun startupAndLastFailureSurviveLogFloodWithBoundedSize() {
        val journal=TransportEvidence()
        journal.append("USB startup marker")
        repeat(2000) { journal.append("NCM sample=$it "+"x".repeat(160)) }
        journal.append("NCM evidence failure: final boundary")
        val snapshot=journal.snapshot()
        assertTrue(snapshot.startsWith("USB startup marker"))
        assertTrue(snapshot.contains("NCM evidence failure: final boundary"))
        assertTrue(snapshot.length<85000)
        assertFalse(snapshot.contains("NCM sample=100 "))
    }
    @Test fun credentialsAndIdentifiersAreRemovedBeforeRetention() {
        val journal=TransportEvidence()
        journal.append("USB serial=private-device; hostid=private-host")
        journal.append("X-Upload-Token: private-token")
        journal.append("NCM failure /Users/private-user/secret.txt")
        val value=journal.snapshot()
        listOf("private-device","private-host","private-token","private-user").forEach { assertFalse(value.contains(it)) }
    }
    @Test fun iapKeepaliveIsExcludedAndAudioIsRetained() {
        assertFalse(TransportEvidence.relevant("iAP2 rx=5001"))
        assertTrue(TransportEvidence.relevant("NCM evidence failure"))
        assertTrue(TransportEvidence.relevant("Audio gain channel=MEDIA"))
        assertTrue(TransportEvidence.relevant("诊断；音频丢包=0"))
    }
    @Test fun persistedSnapshotCanBeReadAfterRestartAndOversizeFileIsRejected() {
        val file=java.io.File.createTempFile("diplay-evidence-test", ".txt")
        try {
            val journal=TransportEvidence();journal.append("USB startup");journal.append("NCM evidence failure: boundary")
            file.writeText(journal.snapshot(),Charsets.UTF_8)
            assertEquals(journal.snapshot(),TransportEvidence.readPrevious(file))
            file.writeText("x".repeat(300001))
            assertEquals("",TransportEvidence.readPrevious(file))
        } finally { file.delete() }
    }
}
