"""Create a traceable API-18 protocol snapshot without altering the upstream checkout."""
from pathlib import Path
import json
import shutil
import hashlib

root = Path(__file__).resolve().parents[2]
upstream = root / 'shared/src/main/java/com/shilapi/xcertplay'
out = Path(__file__).resolve().parent / 'build/upstream'
out.mkdir(parents=True, exist_ok=True)
chosen = list((upstream / 'airplay').glob('*.kt')) + list((upstream / 'iap2').rglob('*.kt'))
transport = ['BlockingDuplexByteStream', 'BluetoothRfcommDuplexStream', 'I2cTransport',
             'Iap2CsmChannel', 'Iap2LinkChannel', 'Iap2LinkEngine', 'Iap2IdentificationClient',
             'Iap2WirelessControlClient', 'Iap2WiredControlClient', 'Iap2ControlDeadline',
             'Iap2LocationClient', 'Iap2VehicleStatus', 'VehicleSpeedNmea', 'Iap2UsbMuxHost',
             'NcmUsbBridge', 'NcmFunctionDiscovery', 'IphoneCarPlayConfiguration', 'Ntb16Codec', 'EthernetIpv6Codec',
             'LockdownCarKitClient', 'LockdownPairingClient', 'LockdownPairRecord', 'LockdownPlistChannel',
             'LockdownTlsEngineFactory', 'TlsDuplexChannel']
chosen += [upstream / 'transport' / (name + '.kt') for name in transport]
chosen += [upstream / 'mfi' / (name + '.kt') for name in
           ['MfiAuthenticationClient', 'Iap2MfiAuthenticationClient']]
chosen += [upstream / 'media/MediaCodecSupport.kt']
chosen += [upstream / 'network/Ipv6NcmBridge.kt']
chosen += [upstream / 'media/TouchLatencyProbe.kt']
chosen += [upstream / 'media' / (name + '.kt') for name in ['AudioChannelMapping', 'MediaAudioBuffer', 'AudioBufferProgress']]
manifest = []
for source in chosen:
    text = source.read_text()
    original = text
    # Retain tested IPv4 wildcard listeners for wireless and choose IPv6 only
    # for a wired IPv6 session. SDK18 may reject IPv6 binds on an IPv4-only stack.
    if source.name == 'IapTunnel.kt':
        start = text.index('        val secondaryAddress =')
        end = text.index('        servers.forEach', start)
        text = text[:start] + text[end:]
    if source.name in ('AudioStream.kt', 'ScreenStream.kt'):
        text = text.replace('InetAddress.getByName("::")', 'bindAddress')
        marker = ') : Closeable {'
        # Add a constructor bind address selected by the current session network family.
        idx = text.index(marker)
        text = text[:idx] + (', ' if source.name == 'ScreenStream.kt' else '') + '    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),\n' + text[idx:]
    else:
        text = text.replace('session.localAddress ?: InetAddress.getByName("::")', 'session.localAddress ?: InetAddress.getByName("0.0.0.0")')
    if source.name == 'AirPlaySession.kt':
        text = text.replace('InetAddress.getByName("::")', 'local.airuize.receiver.LegacyBindAddress.any(localAddress)')
        text = text.replace('ntp.listen()', 'ntp.listen(local.airuize.receiver.LegacyBindAddress.any(localAddress))')
    if source.name == 'NtpClock.kt':
        text = text.replace('fun listen(): Int', 'fun listen(bindAddress: InetAddress = InetAddress.getByName("0.0.0.0")): Int')
        text = text.replace('InetAddress.getByName("::")', 'bindAddress')
    if source.name == 'CarPlayMediaEngine.kt':
        text = text.replace('AudioStream(key, type, session::logDebug)', 'AudioStream(key, type, session::logDebug, local.airuize.receiver.LegacyBindAddress.any(session.localAddress))')
        text = text.replace('ScreenStream(key, session::logDebug)', 'ScreenStream(key, session::logDebug, local.airuize.receiver.LegacyBindAddress.any(session.localAddress))')
        # ScreenStream binds to session local address through its caller (see constructor patch below).
    if source.name in ('NcmFunctionDiscovery.kt', 'IphoneCarPlayConfiguration.kt'):
        text = text.replace('import android.hardware.usb.UsbConfiguration', 'import local.airuize.receiver.LegacyUsbConfiguration as UsbConfiguration\nimport local.airuize.receiver.alternateSetting')
    if source.name == 'IphoneCarPlayConfiguration.kt':
        start = text.index('    fun find(device:')
        end = text.index('        val chosen =', start)
        text = text[:start] + '    fun find(configurations: List<UsbConfiguration>): UsbConfiguration? {\n' + text[end:]
    if source.name == 'LockdownTlsEngineFactory.kt':
        text = text.replace('                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = null }\n', '')
        text = text.replace('KeyManagerFactory.getInstance("PKIX")', 'KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())')
    if source.name == 'NcmUsbBridge.kt':
        text = text.replace('import android.hardware.usb.UsbRequest', 'import android.hardware.usb.UsbRequest\nimport local.airuize.receiver.alternateSetting')
        start = text.index('    private fun readChunk(')
        end = text.index('    private fun failSession(', start)
        text = text[:start] + '''    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        val transferred = connection.bulkTransfer(inEndpoint, readBuffer, minOf(16384, readBuffer.size), timeoutMillis.coerceIn(1, 250).toInt())
        checkOpen()
        return transferred.takeIf { it > 0 }
    }

''' + text[end:]
        text = text.replace('connection.setInterface(function.data)', 'local.airuize.receiver.LegacyUsbPlatform.selectInterface(connection, function.data)')
    if source.name == 'Ipv6NcmBridge.kt':
        text = text.replace('    private val onError: (Throwable) -> Unit,', '    private val onError: (Throwable) -> Unit,\n    private val report: (String) -> Unit = {},')
        text = text.replace('    private var loggedInbound = false', '    private var inboundFrames = 0L\n    private var outboundFrames = 0L\n    private var loggedInbound = false')
        text = text.replace('                peerMac = ipv6.sourceMac', '                peerMac = ipv6.sourceMac\n                inboundFrames++\n                if(inboundFrames == 1L || inboundFrames % 500 == 0L) report("NCM IPv6 inboundFrames=$inboundFrames")')
        text = text.replace('                ncm.send(frame, WRITE_TIMEOUT_MILLIS)', '                ncm.send(frame, WRITE_TIMEOUT_MILLIS)\n                outboundFrames++\n                if(outboundFrames == 1L || outboundFrames % 500 == 0L) report("NCM IPv6 outboundAttempts=$outboundFrames")')
        text = text.replace('                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)', '''                val deadline = System.nanoTime() + 500_000_000L
                while(running.get()) {
                    try { output.write(frame, ipv6.payloadOffset, ipv6.payloadLength); break }
                    catch(error: IOException) {
                        if(!local.airuize.receiver.LegacyTun.isWouldBlock(error) || System.nanoTime() >= deadline) throw error
                        LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    }
                }''')
        text = text.replace('                val length = input.read(buffer)', '                val length = try { input.read(buffer) } catch (error: IOException) {\n                    if (running.get() && local.airuize.receiver.LegacyTun.isWouldBlock(error)) { LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS); continue }\n                    throw error\n                }')
    # StandardCharsets is API 19; java.util.Base64 is API 26.
    text = text.replace('import java.util.Base64', 'import local.airuize.receiver.LegacyBase64 as Base64')
    text = text.replace('import java.nio.charset.StandardCharsets', 'import local.airuize.receiver.LegacyCharsets as StandardCharsets')
    # AutoCloseable is API 19. These classes do not need its larger close contract.
    text = text.replace('AutoCloseable', 'java.io.Closeable')
    text = text.replace('KeyEvent.KEYCODE_VOICE_ASSIST', '231')
    text = text.replace('java.lang.Long.toUnsignedString', 'local.airuize.receiver.LegacyNumbers.unsigned')
    text = text.replace('java.lang.Long::toUnsignedString', 'local.airuize.receiver.LegacyNumbers::unsigned')
    text = text.replace('Integer.toUnsignedString', 'local.airuize.receiver.LegacyNumbers.unsigned')
    text = text.replace('Math.floorDiv', 'local.airuize.receiver.LegacyNumbers.floorDiv')
    text = text.replace('Math.floorMod', 'local.airuize.receiver.LegacyNumbers.floorMod')
    if source.name == 'AirPlayInfoPlist.kt':
        # The SDK18 sink implements PCM and AAC only: do not negotiate unsupported Opus streams.
        text = text.replace('val opus = 0x70000000', 'val opus = if (allowOpus) 0x70000000 else 0')
        text = text.replace('audioFormats(config.entertainmentSampleRate, config.microphone)', 'audioFormats(config.entertainmentSampleRate, config.microphone, config.opus)')
        text = text.replace('        microphone: Boolean,', '        microphone: Boolean,\n        allowOpus: Boolean,')
    if source.name == 'AirPlayConfig.kt':
        text = text.replace('    val videoInCar: Boolean = false,', '    val videoInCar: Boolean = false,\n    val opus: Boolean = false,')
    if source.name == 'CarPlayMediaEngine.kt':
        # Keep upstream start timing; expose why an input stream cannot be created.
        target = '        val key = outputKey(session, stream) ?: return null'
        assert target in text
        audio_start = text.index('    override fun onAudio(')
        text = text[:audio_start] + text[audio_start:].replace(target, '''        session.logDebug("receiver audio requested type=$type purpose=${local.airuize.receiver.AudioDiagnostics.purpose(audioType)} microphoneEnabled=$microphoneEnabled")
        val key = outputKey(session, stream) ?: run {
            session.logDebug("receiver microphone unavailable reason=missing-output-key")
            return null
        }''', 1)
        target = '        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null'
        assert target in text
        text = text.replace(target, '''        fun unavailable(reason: String): MicrophoneConfig? {
            session.logDebug("receiver microphone unavailable reason=$reason")
            return null
        }
        if (!microphoneEnabled) return unavailable("disabled")
        if (type != STREAM_TYPE_MAIN_AUDIO) return unavailable("stream-type")''')
        text = text.replace('if (format.audioType != "telephony" && format.audioType != "speechrecognition") return null',
            'if (format.audioType != "telephony" && format.audioType != "speechrecognition") return unavailable("audio-purpose")')
        text = text.replace('val port = (stream["dataPort"] as? Number)?.toInt() ?: return null',
            'val port = (stream["dataPort"] as? Number)?.toInt() ?: return unavailable("missing-port")')
        text = text.replace('if (port !in 1..65535) return null', 'if (port !in 1..65535) return unavailable("invalid-port")')
        text = text.replace('val host = session.remoteAddress ?: return null', 'val host = session.remoteAddress ?: return unavailable("missing-peer")')
        text = text.replace('val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null',
            'val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return unavailable("missing-input-key")')
        text = text.replace('        if (microphone != null) pendingMicrophone[streamKey] = microphone', '''        if (microphone != null) {
            pendingMicrophone[streamKey] = microphone
            session.logDebug("receiver microphone negotiated waitingForAudio=true")
        }''')
        target = '        val microphone = microphoneConfig(session, type, stream, format)'
        assert target in text
        text = text.replace(target, target + '\n        session.logDebug("receiver audio setup type=$type codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} purpose=${local.airuize.receiver.AudioDiagnostics.purpose(audioType)} microphone=${microphone != null}")')
        target = '        val audio = AudioStream(key, type, session::logDebug, local.airuize.receiver.LegacyBindAddress.any(session.localAddress))'
        assert target in text
        text = text.replace(target, '        var rejectedPackets = 0\n' + target)
        target = '                    capture?.record(wire, rtp, sample, error)'
        assert target in text
        text = text.replace(target, target + '''
                    if (error != null) {
                        rejectedPackets++
                        if (rejectedPackets <= 3 || rejectedPackets % 100 == 0)
                            session.logDebug("receiver audio rejected type=$type count=$rejectedPackets")
                    }''')
    if source.name in ('PairSetup.kt', 'PairVerify.kt'):
        # The upstream responder converts crypto exceptions to TLV errors. Preserve
        # that behavior but make the exception location available to exported logs.
        phase = 'pair-setup' if source.name == 'PairSetup.kt' else 'pair-verify'
        text = text.replace('    private val pairings: PairingStore,',
            '    private val pairings: PairingStore,\n    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },')
        text = text.replace('        } catch (_: Exception) {\n            err(state ?: 0)',
            f'        }} catch (error: Exception) {{\n            onFailure("{phase}", error)\n            err(state ?: 0)\n        }} catch (error: LinkageError) {{\n            onFailure("{phase}", error)\n            err(state ?: 0)')
    if source.name == 'AirPlaySession.kt':
        # Call the original helper directly; record booleans without changing action order or failure semantics.
        target = '''    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }'''
        assert target in text
        text = text.replace(target, target.replace('        sendCommand(', '        val down = sendCommand(', 1)
            .replace('        sendCommand(', '        val up = sendCommand(', 1)
            .replace('    }', '        debugLog("receiver siri request down=$down up=$up")\n    }'))
        text = text.replace('    fun onDebugLog(message: String) {}',
            '    fun onDebugLog(message: String) {}\n    fun onControlFailure(stage: String, error: Throwable) {}')
        text = text.replace('PairSetup(identity, pairings)', 'PairSetup(identity, pairings, ::reportControlFailure)')
        text = text.replace('PairVerify(identity, pairings)', 'PairVerify(identity, pairings, ::reportControlFailure)')
        text = text.replace('            eventCipher = ControlCipher(readKey, writeKey)',
            '            eventCipher = ControlCipher(readKey, writeKey)\n            debugLog("airplay event encryption ready")')
        for stage, marker in [('event-accept', 'airplay event accept failed'),
                              ('event-decrypt', 'airplay event decrypt failed encrypted=${encrypted.size}'),
                              ('event-read', 'airplay event read failed')]:
            target = f'Log.e(TAG, "{marker}", error)'
            assert target in text
            text = text.replace(target, f'run {{ reportControlFailure("{stage}", error); {target} }}')
        text = text.replace('                        closeReason = "control decrypt failed:',
            '                        reportControlFailure("control-decrypt", error)\n                        closeReason = "control decrypt failed:')
        text = text.replace('                    } catch (error: Exception) {\n                        Log.e(',
            '                    } catch (error: Exception) {\n                        reportControlFailure("request-handler", error)\n                        Log.e(')
        text = text.replace('            closeReason = "control I/O failed:',
            '            reportControlFailure("control-io", error)\n            closeReason = "control I/O failed:')
        text = text.replace('        } finally {\n            debugLog("airplay control closing',
            '        } catch (error: LinkageError) {\n            closeReason = "control runtime failed"\n            reportControlFailure("control-runtime", error)\n        } finally {\n            debugLog("airplay control closing')
        for phase, responder in [('pair-setup', 'pairSetup'), ('pair-verify', 'pairVerify')]:
            text = text.replace(f'body = {responder}.handle(request.body),',
                f'body = pairingResponse("{phase}", request.body, {responder}.handle(request.body)),')
        marker = '    private fun debugLog(message: String, uiVisible: Boolean = true) {'
        additions = '''    private fun reportControlFailure(stage: String, error: Throwable) {
        try { listener.onControlFailure(stage, error) }
        catch (_: Exception) {}
    }

    private fun pairingResponse(phase: String, request: ByteArray, response: ByteArray): ByteArray {
        fun value(body: ByteArray, key: Int): String = try {
            Tlv8Codec.decode(body)[key]?.firstOrNull()?.let { (it.toInt() and 255).toString() } ?: "none"
        } catch (_: Exception) { "invalid" }
        debugLog("airplay pairing phase=$phase requestState=${value(request, 6)} responseState=${value(response, 6)} error=${value(response, 7)}")
        return response
    }

'''
        assert marker in text
        text = text.replace(marker, additions + marker)
    text = text.replace('if (failure != null) throw failure', 'failure?.let { throw it }')
    # SDK18 sockets predate their Closeable interfaces.
    import re
    text = re.sub(r'fun safeClose\((\w+): Closeable\?\)', r'fun safeClose(\1: Any?)', text)
    if 'fun safeClose' in text:
        text = text.replace('closeable?.close()', 'local.airuize.receiver.LegacyCloseables.close(closeable)')
    if source.name == 'Iap2LocationClient.kt':
        # A stop received on Bluetooth also stops the independent Wi-Fi provider.
        target = """        if (continueRequest && continued && request?.components == null) {
            active = false
            sentLogged = false
            return
        }"""
        assert target in text
        text = text.replace(target, target.replace('            return', '            provider?.stop()\n            return'))
        text = text.replace('java.time.Instant.ofEpochMilli(millis)\n            .atZone(java.time.ZoneOffset.UTC)',
                            'java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }')
        for old, new in [('hour', 'HOUR_OF_DAY'), ('minute', 'MINUTE'), ('second', 'SECOND'),
                         ('dayOfMonth', 'DAY_OF_MONTH'), ('year', 'YEAR')]:
            text = text.replace('fields.' + old, 'fields.get(java.util.Calendar.' + new + ')')
        text = text.replace('fields.monthValue', '(fields.get(java.util.Calendar.MONTH) + 1)')
    if source.name == 'Srp6a.kt':
        # Kotlin 1.9 requires the compile-time modulus declaration above its first use.
        start = text.index('    private const val N_HEX =')
        end = text.index('\n\n', start)
        modulus = text[start:end]
        text = text[:start] + text[end:]
        text = text.replace('object Srp6a {', 'object Srp6a {\n' + modulus)
    destination = out / source.relative_to(upstream)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text)
    manifest.append({'path': str(source.relative_to(upstream)),
                     'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                     'modified': text != original})
(out.parent / 'upstream-manifest.json').write_text(json.dumps(manifest, indent=2))
# Remove only a previous generated version of the replaced platform-provider implementation.
(out / 'mfi/LocalMfiAuthenticationClient.kt').unlink(missing_ok=True)
licenses = Path(__file__).resolve().parent / 'assets/licenses'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy(root / 'LICENSE', licenses / 'DiPlay-LICENSE')
shutil.copytree(root / 'docs/licenses', licenses / 'upstream', dirs_exist_ok=True)
print('Prepared', len(chosen), 'protocol/media source files')

# Reuse upstream translations without requiring the modern Compose/AndroidX UI runtime.
import xml.etree.ElementTree as ET
translations = {}
resource_manifest = []
for language, directory in [('en','values'),('zh','values-zh-rCN'),('ar','values-ar'),('es','values-es'),('ru','values-ru')]:
    source = root / 'common/src/main/res' / directory / 'strings.xml'
    translations[language] = {entry.attrib['name']: ''.join(entry.itertext()).replace("\\'", "'").replace('\\n','\n').replace('\\u0020',' ') for entry in ET.parse(source).getroot() if entry.tag == 'string'}
    resource_manifest.append({'path': 'common/src/main/res/' + directory + '/strings.xml', 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
(Path(__file__).resolve().parent / 'assets/upstream-ui-strings.json').write_text(json.dumps(translations, ensure_ascii=False))
(out.parent / 'upstream-ui-manifest.json').write_text(json.dumps(resource_manifest, indent=2))
