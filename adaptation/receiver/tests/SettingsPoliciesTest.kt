package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
class SettingsPoliciesTest {
    @Test fun vendorStreamsAndAutomaticRoutingRemainIndependent() {
        assertEquals(3,AudioOverridePolicy.resolve("MEDIA",3,0,15))
        assertEquals(15,AudioOverridePolicy.resolve("NAVIGATION",3,8,15))
        assertEquals(0,AudioOverridePolicy.resolve("PHONE",0,8,15))
        assertEquals(3,AudioOverridePolicy.resolve("ASSISTANT",3,8,15))
        assertEquals(20,AudioOverridePolicy.resolve("MEDIA",3,20,0))
    }
    @Test fun invalidSavedStreamDoesNotReachAudioTrack() {
        assertEquals(3,AudioOverridePolicy.resolve("MEDIA",3,21,0))
        assertEquals(3,AudioOverridePolicy.resolve("NAVIGATION",3,0,-1))
    }
    @Test fun reportFolderCannotEscapeSharedStorage() {
        assertTrue(ReportFolderPolicy.valid("DiPlayLegacy"))
        assertTrue(ReportFolderPolicy.valid("Reports/CarPlay"))
        for(path in listOf("","/tmp/report","../private","a/../../private","a//b","a/./b","a\\b","a\nb")) assertFalse(path,ReportFolderPolicy.valid(path))
    }
}
