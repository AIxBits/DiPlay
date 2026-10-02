package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig

class LegacyCompatibilityTest {
    @Test fun siriInvocationsStayOrderedWhileRequestsArriveTogether() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(2)
        val actions = java.util.Collections.synchronizedList(arrayListOf<String>())
        val serial = SiriRequestSerialiser()
        try {
            assertTrue(serial.submit({ true }, {
                actions.add("A-start"); started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); actions.add("A-end")
            }, { error -> assertNull(error); completed.countDown() }))
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(serial.submit({ true }, { actions.add("B-start"); actions.add("B-end") }, { completed.countDown() }))
            release.countDown()
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("A-start", "A-end", "B-start", "B-end"), actions.toList())
        } finally { release.countDown(); serial.close() }
    }
    @Test fun siriFailureDoesNotKillNextInvocationOrOverrideUpstreamSequence() {
        val done = java.util.concurrent.CountDownLatch(2)
        val actions = arrayListOf<Int>()
        val serial = SiriRequestSerialiser()
        try {
            assertTrue(serial.submit({ true }, { actions.add(2); throw java.io.IOException("not logged") },
                { error -> assertEquals("IOException", error); done.countDown() }))
            assertTrue(serial.submit({ true }, { actions.add(2); actions.add(3) }, { error -> assertNull(error); done.countDown() }))
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf(2, 2, 3), actions)
        } finally { serial.close() }
    }
    @Test fun siriDiscardsEndedSessionAndRejectsClosedQueue() {
        val discarded = java.util.concurrent.CountDownLatch(1)
        val serial = SiriRequestSerialiser()
        try {
            assertTrue(serial.submit({ false }, { fail("stale session invoked") }, { fail("stale request completed") }, { discarded.countDown() }))
            assertTrue(discarded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { serial.close() }
        assertFalse(serial.submit({ true }, {}, { fail("closed queue accepted") }))
    }
    @Test fun gpsReporterSendsInitialFixAndStopsSharedWifiRequest() {
        var now = 0L
        val shared = com.shilapi.xcertplay.transport.Iap2LocationRequest()
        class Provider : com.shilapi.xcertplay.transport.Iap2LocationProvider {
            var running = false
            override fun start(): Boolean { running = true; return true }
            override fun stop() { running = false }
            override fun latestNmea(): String? = if (running) LegacyLocationEncoder.encode(
                com.shilapi.xcertplay.transport.CarPlayLocationFix(30.0, 110.0, timestampMillis = 1704067200000L)) else null
        }
        val bt = Provider(); val wifi = Provider()
        val sent = arrayListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        val reporter = com.shilapi.xcertplay.transport.Iap2LocationReporter(bt, {}, shared, nanoTime = { now })
        reporter.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0xfffa, byteArrayOf())) { sent.add(it) }
        assertEquals(1, sent.size); assertEquals(0xfffb, sent.single().messageId)
        repeat(5) { reporter.tick { sent.add(it) } }
        assertEquals(1, sent.size)
        val tunnel = com.shilapi.xcertplay.transport.Iap2LocationReporter(wifi, {}, shared, true, { now })
        tunnel.tick { sent.add(it) }
        assertTrue(wifi.running); assertEquals(2, sent.size)
        reporter.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0xfffc, byteArrayOf())) { sent.add(it) }
        now = 2_000_000_000L
        tunnel.tick { sent.add(it) }; reporter.tick { sent.add(it) }
        assertFalse(bt.running); assertFalse(wifi.running)
        assertNull(shared.components); assertEquals(2, sent.size)
    }
    @Test fun steeringVoiceUsesVerifiedFirmwareCodesAndSignedMcuByte() {
        for (code in listOf(93, 221, -35, 118)) assertTrue(VoiceKeyPolicy.isMcuVoice(code))
        for (code in listOf(16, 17, 2, 35, 304, 312, 349, -163)) assertFalse(VoiceKeyPolicy.isMcuVoice(code))
        for (code in listOf(219, 231, 1093, 965, 1221, 1118)) assertTrue(VoiceKeyPolicy.isWindowVoice(code))
        for (code in listOf(3, 4, 24, 25, 85, 304, 312)) assertFalse(VoiceKeyPolicy.isWindowVoice(code))
    }
    @Test fun steeringVoiceDeduplicatesWindowAndMcuDeliveryButAllowsNextPress() {
        val gate = VoiceKeyGate()
        assertTrue(gate.accept(1000))
        assertFalse(gate.accept(1010))
        assertFalse(gate.accept(1599))
        assertTrue(gate.accept(1600))
        assertFalse(gate.accept(1500))
        assertTrue(gate.accept(2300))
        assertFalse(VoiceKeyGate().accept(-1))
    }
    @Test fun handoffWatchdogMirrorsUpstreamAndResetsFailedGeneration() {
        val handoff = WirelessHandoff()
        assertEquals(WirelessHandoff.TimeoutAction.IGNORE, handoff.timeoutAction(false, false))
        handoff.request("disableBluetooth")
        assertEquals(WirelessHandoff.TimeoutAction.IGNORE, handoff.timeoutAction(true, false))
        assertEquals(WirelessHandoff.TimeoutAction.KEEP_VIDEO, handoff.timeoutAction(false, true))
        handoff.tunnelReady()
        assertTrue(handoff.claimCompletion())
        assertEquals(WirelessHandoff.TimeoutAction.IGNORE, handoff.timeoutAction(false, false))
        handoff.reset()
        handoff.request("disableBluetooth")
        assertEquals(WirelessHandoff.TimeoutAction.FAIL, handoff.timeoutAction(false, false))
        handoff.tunnelReady()
        assertFalse(handoff.claimCompletion())
        handoff.reset()
        assertFalse(handoff.claimCompletion())
        handoff.request("disableBluetooth"); handoff.tunnelReady()
        assertTrue(handoff.claimCompletion())
    }
    @Test fun vehicleGpsEncodingLeavesUnavailableMeasurementsBlankAndKeepsChecksums() {
        val fix = com.shilapi.xcertplay.transport.CarPlayLocationFix(-30.25, 110.5, timestampMillis = 1704067200000L)
        val lines = LegacyLocationEncoder.encode(fix).trim().split("\r\n")
        assertEquals(2, lines.size)
        for (line in lines) {
            val body = line.substring(1).substringBefore('*')
            var checksum = 0
            body.forEach { checksum = checksum xor it.code }
            assertEquals(String.format(java.util.Locale.US, "%02X", checksum), line.substringAfter('*'))
        }
        val gga = lines[0].substringBefore('*').split(',')
        assertEquals("3015.0000", gga[2]); assertEquals("S", gga[3])
        assertEquals("11030.0000", gga[4]); assertEquals("E", gga[5])
        for (i in 7..12) assertEquals("", gga[i])
        val rmc = lines[1].substringBefore('*').split(',')
        assertEquals("", rmc[7]); assertEquals("", rmc[8])
        assertEquals("010124", rmc[9])
        val moving = LegacyLocationEncoder.encode(fix.copy(speedMetersPerSecond = 10.0, bearingDegrees = 90.0))
        assertTrue(moving.contains(",19.44,90.00,010124,"))
    }
    @Test fun iapTunnelDiagnosticStagesDoNotExportSeedOrAddress() {
        assertEquals("Wi-Fi iAP隧道SETUP已请求（不记录UUID/密钥种子）", AirPlayDiagnostics.summary("AirPlay iAP SETUP uuid=private seed=123 streamConnectionID=456"))
        assertEquals("Wi-Fi iAP隧道监听已建立", AirPlayDiagnostics.summary("AirPlay iAP tunnel listening address=192.0.2.1 port=1234"))
    }
    @Test fun audioSetupDiagnosticsRejectPayloadsAndUnknownNames() {
        assertNotNull(AirPlayDiagnostics.summary("receiver siri request down=true up=false"))
        assertNull(AirPlayDiagnostics.summary("receiver siri request down=true up=false text=private"))
        assertNotNull(AirPlayDiagnostics.summary("receiver microphone unavailable reason=missing-input-key"))
        assertNull(AirPlayDiagnostics.summary("receiver microphone unavailable reason=missing-input-key private=secret"))
        assertNull(AirPlayDiagnostics.summary("receiver microphone unavailable reason=private"))
        assertNotNull(AirPlayDiagnostics.summary("receiver audio requested type=100 purpose=speechrecognition microphoneEnabled=true"))
        assertNull(AirPlayDiagnostics.summary("receiver audio requested type=100 purpose=speechrecognition microphoneEnabled=true address=private"))
        val setup = "receiver audio setup type=100 codec=LPCM rate=16000 channels=1 purpose=speechrecognition microphone=true"
        assertTrue(AirPlayDiagnostics.summary(setup)!!.contains("speechrecognition"))
        assertNull(AirPlayDiagnostics.summary(setup + " private=secret"))
        assertEquals("speechrecognition", AudioDiagnostics.purpose("speechRecognition"))
        assertEquals("其他", AudioDiagnostics.purpose("private-destination"))
        assertTrue(AirPlayDiagnostics.summary("receiver audio rejected type=100 count=3")!!.contains("认证/解析失败"))
    }
    @Test fun gpsProbeRejectsStaleMockNetworkAndInvalidCoordinates() {
        assertTrue(GpsDiagnostics.freshGps("gps", 30.12345, 110.12345, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", 30.12345, 110.12345, false, 10001))
        assertFalse(GpsDiagnostics.freshGps("gps", 30.12345, 110.12345, true, 1000))
        assertFalse(GpsDiagnostics.freshGps("network", 30.12345, 110.12345, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", Double.NaN, 110.0, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", 30.0, Double.POSITIVE_INFINITY, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", 91.0, 110.0, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", 30.0, 181.0, false, 1000))
        assertFalse(GpsDiagnostics.freshGps("gps", 30.0, 110.0, false, -1))
    }
    @Test fun gpsMetadataCannotExposeCoordinatesOrCustomProviderNames() {
        val line = GpsDiagnostics.describe("private-provider", 1000, Float.NaN, false, true, false)
        assertFalse(line.contains("private-provider"))
        assertTrue(line.contains("精度米=未知"))
        assertTrue(line.contains("有速度=true"))
        assertTrue(GpsDiagnostics.describe("gps", 1000, Float.POSITIVE_INFINITY, false, false, false).contains("精度米=未知"))
    }
    @Test fun carPlayInfoMatchesOriginalExceptUnsupportedOpus() {
        for (microphone in listOf(false, true)) {
            val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
                AirPlayDisplayConfig(1024, 600, fps = 30), microphone = microphone)
            val expected = com.shilapi.xcertplay.airplay.BaselineAirPlayInfoPlist.build(config).toMutableMap()
            expected["audioFormats"] = (expected["audioFormats"] as List<*>).map { entry ->
                (entry as Map<*, *>).mapValues { (key, value) ->
                    if (key == "audioInputFormats" || key == "audioOutputFormats")
                        (value as Int) and 0x70000000.inv() else value
                }
            }
            fun comparable(value: Any?): Any? = when (value) {
                is ByteArray -> value.toList()
                is Map<*, *> -> value.mapValues { comparable(it.value) }
                is List<*> -> value.map { comparable(it) }
                else -> value
            }
            assertEquals(comparable(expected), comparable(AirPlayInfoPlist.build(config)))
        }
    }
    @Test fun handoffWaitsForAuthenticatedTunnelAndPhoneRequest() {
        val handoff = WirelessHandoff()
        assertFalse(handoff.claimCompletion())
        assertFalse(handoff.request("requestUI"))
        assertTrue(handoff.request("disableBluetooth"))
        assertFalse(handoff.claimCompletion())
        handoff.tunnelReady()
        assertTrue(handoff.claimCompletion())
        assertFalse(handoff.claimCompletion())
    }
    @Test fun tunnelMayBecomeReadyBeforeHandoffCommand() {
        val handoff = WirelessHandoff()
        handoff.tunnelReady()
        assertFalse(handoff.claimCompletion())
        assertTrue(handoff.request("DISABLE-BLUETOOTH"))
        assertTrue(handoff.claimCompletion())
    }
    @Test fun concurrentHandoffCallbacksReleaseBootstrapOnlyOnce() {
        val handoff = WirelessHandoff()
        handoff.request("disableBluetooth"); handoff.tunnelReady()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { if (handoff.claimCompletion()) count.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, count.get())
    }
    @Test fun eventDiagnosticsExcludeAddressesParametersAndPayloads() {
        assertEquals("AirPlay 事件通道 TCP 已接入", AirPlayDiagnostics.summary("airplay event connection accepted from /private-device:123"))
        assertEquals("AirPlay 事件通道加密已就绪", AirPlayDiagnostics.summary("airplay event encryption ready"))
        val request = AirPlayDiagnostics.summary("airplay event rx POST /command?route=private cseq=5 body=123")!!
        assertTrue(request.contains("事件请求")); assertFalse(request.contains("private"))
        assertEquals("AirPlay 命令：其他类型（不记录名称或参数）", AirPlayDiagnostics.command("private-unknown-command"))
        assertNull(AirPlayDiagnostics.summary("airplay event rx headers=secret bodyHex=secret"))
        assertTrue(AirPlayDiagnostics.command("modesChanged")!!.contains("modesChanged"))
    }
    @Test fun modeDiagnosticsPreserveKnownStatesAndRejectPrivateValues() {
        val params = mapOf<String, Any?>(
            "screen" to 1, "turnByTurn" to 2, "phoneCall" to false,
            "url" to "private://destination", "mainAudio" to "private-address",
            "appStates" to listOf(mapOf("appStateID" to 3, "state" to true, "destination" to "private-route")),
            "modes" to mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 2))),
            "speech" to Double.NaN)
        val line = AirPlayDiagnostics.command("modesChanged", params)!!
        assertTrue(line.contains("turnByTurn=2")); assertTrue(line.contains("appStates[0].state=true"))
        assertTrue(line.contains("modes.resources[0].entity=2"))
        assertFalse(line.contains("private")); assertFalse(line.contains("NaN")); assertFalse(line.contains("destination"))
    }
    @Test fun modeDiagnosticsAreBoundedAndDoNotLeakUnknownSchemas() {
        val entries = (0..999).map { mapOf("appStateID" to it, "state" to true) }
        val line = AirPlayDiagnostics.command("modesChanged", mapOf("appStates" to entries))!!
        assertTrue(line.contains("appStates[7].")); assertFalse(line.contains("appStates[8]."))
        val privateLine = AirPlayDiagnostics.command("modesChanged", mapOf("screen" to Long.MAX_VALUE, "private" to "secret"))!!
        assertEquals("AirPlay 命令：modesChanged；没有可记录的已知状态字段", privateLine)
    }
    @Test fun repeatedVideoConfigDoesNotRestartDecoder() {
        val state = VideoConfigState()
        val initial = byteArrayOf(1, 2, 3, 4)
        assertTrue(state.update(initial))
        val accepted = state.snapshot
        repeat(100) { assertFalse(state.update(initial.copyOf())); assertSame(accepted, state.snapshot) }
        initial[0] = 9
        assertEquals(1, state.snapshot!![0].toInt())
        assertTrue(state.update(initial))
        assertNotSame(accepted, state.snapshot)
        assertEquals(9, state.snapshot!![0].toInt())
    }
    @Test fun airPlayDiagnosticsExcludePayloadsAndPrivatePaths() {
        assertNull(AirPlayDiagnostics.summary("TRACE airplay control rx headers={secret} bodyHex=private"))
        assertNull(AirPlayDiagnostics.summary("airplay /info request={private}"))
        val safe = AirPlayDiagnostics.summary("airplay rx POST /pair-verify?private=id cseq=2 body=140")!!
        assertTrue(safe.contains("/pair-verify"))
        assertFalse(safe.contains("private"))
        assertEquals("AirPlay 响应：status=500；CSeq=2；body=0", AirPlayDiagnostics.summary("airplay tx status=500 cseq=2 body=0"))
    }
    @Test fun closingDiagnosticsDoNotCopyExceptionMessages() {
        assertEquals("AirPlay 控制连接结束：控制通道读写失败", AirPlayDiagnostics.summary("airplay control closing reason=control I/O failed: private-value activeStreams=[]"))
        assertEquals("AirPlay 控制连接结束：对端关闭连接", AirPlayDiagnostics.summary("airplay control closing reason=peer EOF activeStreams=[]"))
    }
    @Test fun pairingDiagnosticReportsOnlyStateAndErrorCodes() {
        val message = "airplay pairing phase=pair-verify requestState=3 responseState=4 error=2"
        assertEquals(message, AirPlayDiagnostics.summary(message))
        assertNull(AirPlayDiagnostics.summary(message + " private=secret"))
    }
    private fun tlv(vararg entries: Pair<Int, ByteArray>) = com.shilapi.xcertplay.airplay.Tlv8Codec.encode(
        entries.map { com.shilapi.xcertplay.airplay.Tlv8Item(it.first, it.second) })
    @Test fun pairSetupStartsWithSrpChallenge() {
        val errors = ArrayList<Throwable>()
        val setup = com.shilapi.xcertplay.airplay.PairSetup(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),
            com.shilapi.xcertplay.airplay.PairingStore()) { _, error -> errors.add(error) }
        val response = com.shilapi.xcertplay.airplay.Tlv8Codec.decode(setup.handle(tlv(6 to byteArrayOf(1))))
        assertTrue(errors.isEmpty())
        assertArrayEquals(byteArrayOf(2), response[6])
        assertEquals(384, response[3]!!.size)
        assertEquals(16, response[2]!!.size)
        assertNull(response[7])
    }
    @Test fun pairVerifyCompletesAndAuthenticatesBothIdentities() {
        val identity = com.shilapi.xcertplay.airplay.AirPlayIdentity.generate()
        val controller = AirPlayCrypto.ed25519Generate()
        val ephemeral = AirPlayCrypto.x25519Generate()
        val store = com.shilapi.xcertplay.airplay.PairingStore()
        val identifier = "test-controller".toByteArray(Charsets.UTF_8)
        store.save("test-controller", controller.publicKey)
        val verify = com.shilapi.xcertplay.airplay.PairVerify(identity, store)
        val second = com.shilapi.xcertplay.airplay.Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        assertArrayEquals(byteArrayOf(2), second[6]); assertNull(second[7])
        val serverPublic = second[3]!!
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, serverPublic)
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val server = com.shilapi.xcertplay.airplay.Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), second[5]!!))
        assertTrue(AirPlayCrypto.ed25519Verify(identity.publicKey, serverPublic + server[1]!! + ephemeral.publicKey, server[10]!!))
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + identifier + serverPublic)
        val encrypted = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to identifier, 10 to signature))
        val fourth = com.shilapi.xcertplay.airplay.Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(3), 5 to encrypted)))
        assertArrayEquals(byteArrayOf(4), fourth[6]); assertNull(fourth[7])
        assertTrue(verify.isVerified)
        assertArrayEquals(shared, verify.shared)
        assertArrayEquals(AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray()), verify.controlKeys!!.readKey)
    }
    @Test fun pairVerifyExportsFailureWithoutChangingTlvErrorBehavior() {
        val errors = ArrayList<Pair<String, Throwable>>()
        val verify = com.shilapi.xcertplay.airplay.PairVerify(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),
            com.shilapi.xcertplay.airplay.PairingStore()) { stage, error -> errors.add(stage to error) }
        val response = com.shilapi.xcertplay.airplay.Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(1), 3 to ByteArray(2))))
        assertEquals("pair-verify", errors.single().first)
        assertNotNull(response[7])
    }
    @Test(timeout = 3000) fun repeatedCloseWaitsUntilListeningPortIsReleased() {
        val gate = CloseGate()
        val server = java.net.ServerSocket(0)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val secondEntered = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val first = Thread { gate.close { entered.countDown(); release.await(); server.close() } }
        val second = Thread { secondEntered.countDown(); gate.close { fail("Disposed twice") }; returned.countDown() }
        try {
            first.start(); assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            second.start(); assertTrue(secondEntered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(returned.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertFalse(server.isClosed)
            release.countDown()
            assertTrue(returned.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(server.isClosed)
        } finally { release.countDown(); first.join(500); second.join(500); server.close() }
    }
    @Test fun transportProgressDoesNotExposePhoneIdentifiers() {
        val line = "iap2 rx=0x4e0e device-transport-identifier bluetooth=00:11:22:33:44:55 usb=0123456789ABCDEF; resending 0x5703"
        val safe = FailureDiagnostics.progress(line)
        assertFalse(safe.contains("00:11:22:33:44:55"))
        assertFalse(safe.contains("0123456789ABCDEF"))
        assertTrue(safe.contains("resending 0x5703"))
    }
    @Test fun failureLocationsDoNotExposeExceptionMessages() {
        val cause = NullPointerException("private-protocol-value")
        cause.stackTrace = arrayOf(StackTraceElement("javax.jmdns.impl.ServiceInfoImpl", "createQualifiedMap", "ServiceInfoImpl.java", 240))
        val outer = IllegalStateException("private-hotspot-password", cause)
        val lines = FailureDiagnostics.lines("发布服务", outer).joinToString("\n")
        assertTrue(lines.contains("发布服务"))
        assertTrue(lines.contains("NullPointerException"))
        assertTrue(lines.contains("ServiceInfoImpl.java:240"))
        assertFalse(lines.contains("private-protocol-value"))
        assertFalse(lines.contains("private-hotspot-password"))
    }
    @Test fun cyclicExceptionCausesAreBounded() {
        val first = Exception("one")
        val second = Exception("two", first)
        first.initCause(second)
        assertEquals(2, FailureDiagnostics.lines("test", first).count { !it.startsWith("  at ") })
    }
    @Test fun unsupportedOpusIsNeverAdvertised() {
        val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "740.11",
            AirPlayDisplayConfig(800, 480), microphone = true)
        val entries = AirPlayInfoPlist.build(config)["audioFormats"] as List<*>
        for (entry in entries) {
            val fields = entry as Map<*, *>
            for (name in listOf("audioInputFormats", "audioOutputFormats")) {
                val value = fields[name] as? Number ?: continue
                assertEquals(0L, value.toLong() and 0x70000000L)
            }
        }
    }
    @Test fun unsignedIdentifiersPreserveAllBits() {
        assertEquals("18446744073709551615", LegacyNumbers.unsigned(-1L))
        assertEquals("9223372036854775808", LegacyNumbers.unsigned(Long.MIN_VALUE))
        assertEquals("4294967295", LegacyNumbers.unsigned(-1))
    }
    @Test fun negativeClockTimestampsRoundDown() {
        assertEquals(-1L, LegacyNumbers.floorDiv(-1, 1_000_000_000))
        assertEquals(999_999_999L, LegacyNumbers.floorMod(-1, 1_000_000_000))
        assertEquals(-2L, LegacyNumbers.floorDiv(-1_000_000_001, 1_000_000_000))
    }
    @Test fun selectedRuntimeIdentityHasMatchingKeyAndCertificate() {
        val root = System.getProperty("diplay.runtime.assets") ?: error("Explicit runtime input required for this test")
        val auth = LocalMfiAuthenticationClient.load(File(root, "offline-mfi"))
        assertEquals(3, auth.protocolMajor())
        assertTrue(auth.readCertificate().isNotEmpty())
        assertEquals(64, auth.signChallenge(ByteArray(32) { it.toByte() }).size)
    }
    @Test fun mediaEncryptionRejectsCorruptedPackets() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val plain = byteArrayOf(1, 2, 3, 4)
        val aad = byteArrayOf(5, 6)
        val encrypted = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, encrypted, aad))
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        try { AirPlayCrypto.chachaOpen(key, nonce, encrypted, aad); fail("Modified packet accepted") }
        catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {}
    }
}
