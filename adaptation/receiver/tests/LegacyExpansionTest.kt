package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
import com.shilapi.xcertplay.airplay.*
class LegacyExpansionTest {
    @Test fun steeringSeparatesShortOpenAndLongSiriWithoutBlockingLongUpgrade() {
        assertEquals(VoiceKeyPolicy.Action.OPEN,VoiceKeyPolicy.mcuAction(93))
        assertEquals(VoiceKeyPolicy.Action.SIRI,VoiceKeyPolicy.mcuAction(221))
        assertEquals(VoiceKeyPolicy.Action.SIRI,VoiceKeyPolicy.mcuAction(-35))
        assertNull(VoiceKeyPolicy.mcuAction(16))
        assertEquals(VoiceKeyPolicy.Action.OPEN,VoiceKeyPolicy.windowAction(219,100,500))
        assertEquals(VoiceKeyPolicy.Action.SIRI,VoiceKeyPolicy.windowAction(219,500,500))
        assertEquals(VoiceKeyPolicy.Action.SIRI,VoiceKeyPolicy.windowAction(965,0,500))
        val gate=VoiceKeyGate()
        assertTrue(gate.accept(1000,VoiceKeyPolicy.Action.OPEN))
        assertTrue(gate.accept(1400,VoiceKeyPolicy.Action.SIRI))
        assertFalse(gate.accept(1410,VoiceKeyPolicy.Action.SIRI))
        assertFalse(gate.accept(1420,VoiceKeyPolicy.Action.OPEN))
        assertTrue(gate.accept(2200,VoiceKeyPolicy.Action.OPEN))
    }

    @Test fun descriptorsPreserveConfigurationIdsAlternateSettingsAndEndpoints() {
        val config=byteArrayOf(9,2,41,0,2,6,0,0x80.toByte(),50)
        val zero=byteArrayOf(9,4,3,0,0,10,0,0,0)
        val one=byteArrayOf(9,4,3,1,2,10,0,0,0)
        val input=byteArrayOf(7,5,0x86.toByte(),2,0,2,0)
        val output=byteArrayOf(7,5,6,2,0,2,0)
        val parsed=UsbDescriptorParser.parse(config+zero+one+input+output).single()
        assertEquals(6,parsed.id); assertEquals(2,parsed.interfaces.size)
        assertEquals(0,parsed.interfaces[0].alternate); assertEquals(1,parsed.interfaces[1].alternate)
        assertEquals(0x86,parsed.interfaces[1].endpoints[0].address)
        assertEquals(512,parsed.interfaces[1].endpoints[0].packet)
    }
    @Test fun descriptorsRejectTruncationAndZeroLengthWithoutLooping() {
        for(bytes in listOf(byteArrayOf(0,4),byteArrayOf(9,2,0),byteArrayOf(1,5),byteArrayOf(9))) {
            try { UsbDescriptorParser.parse(bytes); fail("Malformed descriptor accepted") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun reconnectBackoffIsCappedAndHandlesNegativeAttempt() {
        assertEquals(2000,ReconnectPolicy.delay(-1)); assertEquals(2000,ReconnectPolicy.delay(0))
        assertEquals(4000,ReconnectPolicy.delay(1)); assertEquals(60000,ReconnectPolicy.delay(30))
    }
    @Test fun displayUsesUpstreamPhysicalScalingAndSafeAreaBounds() {
        val display=ReceiverSettings(width=1024,height=600,scale=5,physicalMm=200,inset=120,fps=45).display()
        assertEquals(512,display.widthPixels); assertEquals(300,display.heightPixels)
        assertEquals(100,display.widthPhysicalMm); assertEquals(59,display.heightPhysicalMm)
        assertEquals(AirPlayInsets(75,75,75,75),display.safeArea); assertEquals(45,display.fps)
    }
    @Test fun opusAdvertisementIsExplicitAndPcmSurvivesBothStates() {
        val base=AirPlayConfig("test","02:00:00:00:00:01","02:00:00:00:00:02","740.11",AirPlayDisplayConfig(800,480),microphone=true)
        for(allow in listOf(false,true)) {
            val formats=AirPlayInfoPlist.build(base.copy(opus=allow))["audioFormats"] as List<*>
            val speech=formats.map { it as Map<*,*> }.first { it["audioType"]=="speechRecognition" }
            val mask=(speech["audioInputFormats"] as Number).toInt()
            assertEquals(if(allow) 0x70000000 else 0,mask and 0x70000000)
            assertTrue(mask and 0x154 != 0)
        }
    }
}
