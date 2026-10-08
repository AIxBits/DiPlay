package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.media.AudioChannelMapper
import com.shilapi.xcertplay.media.AudioChannelMappingMode
class SettingsPoliciesTest {
    @Test fun upstreamTelephonyClassificationUsesLegacyVoiceRouteAndFocus() {
        val mapped=AudioChannelMapper.map("telephony",102,AudioChannelMappingMode.MOBILE_COMPATIBLE,3)
        assertEquals("PHONE",mapped.channel.name)
        val automatic=LegacyAudioRoutePolicy.automatic(mapped.channel.name,mapped.streamType)
        assertEquals(0,automatic)
        assertEquals(0,AudioOverridePolicy.resolve(mapped.channel.name,automatic,8,15))
        assertEquals(0,LegacyAudioRoutePolicy.focusStream(mapped.channel.name))
    }
    @Test fun callFixPreservesSiriMediaAndNavigationRoutes() {
        for((purpose,kind) in listOf("speechrecognition" to "ASSISTANT","media" to "MEDIA","default" to "NAVIGATION")) {
            val mapped=AudioChannelMapper.map(purpose,102,AudioChannelMappingMode.MOBILE_COMPATIBLE,3)
            assertEquals(kind,mapped.channel.name)
            assertEquals(3,LegacyAudioRoutePolicy.automatic(kind,mapped.streamType))
            assertEquals(3,LegacyAudioRoutePolicy.focusStream(kind))
        }
    }
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
    @Test fun configurationRequiresKernelSwitchEvenWhenPhoneAlreadyReportsTarget() {
        assertEquals(0x80045505.toInt(),UsbConfigurationPolicy.SET_CONFIGURATION_IOCTL)
        assertFalse(UsbConfigurationPolicy.confirmed(6,false,6))
        assertFalse(UsbConfigurationPolicy.confirmed(6,true,1))
        assertTrue(UsbConfigurationPolicy.confirmed(6,true,6))
        assertTrue(UsbConfigurationPolicy.confirmed(6,true,null))
    }
    @Test fun reportFolderCannotEscapeSharedStorage() {
        assertTrue(ReportFolderPolicy.valid("DiPlayLegacy"))
        assertTrue(ReportFolderPolicy.valid("Reports/CarPlay"))
        for(path in listOf("","/tmp/report","../private","a/../../private","a//b","a/./b","a\\b","a\nb")) assertFalse(path,ReportFolderPolicy.valid(path))
    }
}
