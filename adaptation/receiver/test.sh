#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
kotlin_dir=${DIPLAY_KOTLIN_DIR:-"$workspace_dir/tools/kotlinc"}
dependencies=${DIPLAY_DEPS_DIR:-"$workspace_dir/downloads"}
upstream="$workspace_dir/shared/src/test/java/com/shilapi/xcertplay"
build_dir="$project_dir/build"
mkdir -p "$build_dir/test-support"
python3 - "$workspace_dir" "$build_dir" <<'PY'
from pathlib import Path
import sys
workspace, build = map(Path, sys.argv[1:])
original = workspace/'shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt'
(build/'test-support/BaselineAirPlayInfoPlist.kt').write_text(original.read_text().replace('object AirPlayInfoPlist {', 'object BaselineAirPlayInfoPlist {'))
PY
"$jdk_dir/bin/javac" -source 8 -target 8 -d "$build_dir/test-support" "$project_dir/test-support/android/util/Log.java"
cp="$build_dir/receiver.jar:$dependencies/junit-4.13.2.jar:$dependencies/hamcrest-core-1.3.jar:$dependencies/bcprov-jdk15to18-1.79.jar"
"$jdk_dir/bin/java" -Xmx2g -cp "$kotlin_dir/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$kotlin_dir" -jdk-home "$jdk_dir" -jvm-target 1.8 -Xfriend-paths="$build_dir/receiver.jar" \
    -classpath "$cp" -d "$build_dir/tests.jar" "$project_dir/tests" "$build_dir/test-support/BaselineAirPlayInfoPlist.kt" \
    "$upstream/media/AudioBufferProgressTest.kt" \
    "$upstream/media/MediaAudioBufferTest.kt" \
    "$upstream/airplay/AirPlayDisplaySettingsTest.kt" \
    "$upstream/airplay/CarPlayDisplayScaleTest.kt" \
    "$upstream/transport/VehicleSpeedNmeaTest.kt" \
    "$upstream/transport/Iap2WiredControlClientTest.kt" \
    "$upstream/transport/EthernetIpv6CodecTest.kt" \
    "$upstream/transport/Ntb16CodecTest.kt" \
    "$upstream/media/MediaCodecSupportTest.kt" "$upstream/airplay/MicrophonePacketizerTest.kt" \
    "$upstream/airplay/BplistCodecDateTest.kt" "$upstream/iap2/Iap2ProtocolTest.kt" \
    "$upstream/transport/Iap2LocationClientTest.kt" "$upstream/transport/Iap2WirelessControlClientTest.kt"
# Synthetic, short-lived test identity: never import a real accessory key into tests.
mkdir -p "$build_dir/test-identity/offline-mfi"
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$build_dir/test-identity/private.pem"
openssl pkcs8 -topk8 -nocrypt -in "$build_dir/test-identity/private.pem" -outform DER -out "$build_dir/test-identity/offline-mfi/identity.pk8"
openssl req -new -x509 -key "$build_dir/test-identity/private.pem" -subj '/CN=Synthetic DiPlay Test' -days 1 -outform DER -out "$build_dir/test-identity/offline-mfi/certificate.p7b"
"$jdk_dir/bin/java" -Djava.net.preferIPv4Stack=true -Ddiplay.runtime.assets="$build_dir/test-identity" \
    -cp "$build_dir/test-support:$build_dir/tests.jar:$cp:$kotlin_dir/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore \
    com.shilapi.xcertplay.media.MediaCodecSupportTest \
    com.shilapi.xcertplay.airplay.MicrophonePacketizerTest \
    com.shilapi.xcertplay.airplay.BplistCodecDateTest \
    com.shilapi.xcertplay.iap2.Iap2ProtocolTest \
    com.shilapi.xcertplay.transport.Iap2LocationClientTest \
    com.shilapi.xcertplay.transport.Iap2WirelessControlClientTest \
    com.shilapi.xcertplay.media.AudioBufferProgressTest \
    com.shilapi.xcertplay.media.MediaAudioBufferTest \
    com.shilapi.xcertplay.airplay.AirPlayDisplaySettingsTest \
    com.shilapi.xcertplay.airplay.CarPlayDisplayScaleTest \
    com.shilapi.xcertplay.transport.VehicleSpeedNmeaTest \
    com.shilapi.xcertplay.transport.Iap2WiredControlClientTest \
    com.shilapi.xcertplay.transport.EthernetIpv6CodecTest \
    com.shilapi.xcertplay.transport.Ntb16CodecTest \
    local.airuize.receiver.SettingsPoliciesTest \
    local.airuize.receiver.VideoSurfaceStateTest \
    local.airuize.receiver.ConnectionStartPolicyTest \
    local.airuize.receiver.LegacyVehicleSpeedTest \
    local.airuize.receiver.LegacyExpansionTest \
    local.airuize.receiver.LegacyCompatibilityTest \
    local.airuize.receiver.LegacyIpv4TransportTest

# A separate JVM is required: preferIPv4Stack is read at network startup.
"$jdk_dir/bin/java" -Djava.net.preferIPv4Stack=false -Ddiplay.runtime.assets="$build_dir/test-identity" \
    -cp "$build_dir/test-support:$build_dir/tests.jar:$cp:$kotlin_dir/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore \
    local.airuize.receiver.LegacyIpv6TransportTest
