#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
sdk_dir=${DIPLAY_PROBE_SDK_DIR:-"$workspace_dir/tools/android"}
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
build_tools=${DIPLAY_BUILD_TOOLS_DIR:-"$sdk_dir/android-14"}
platform_jar=${DIPLAY_PLATFORM_JAR:-"$sdk_dir/android-4.3.1/android.jar"}
android_min_sdk=${DIPLAY_MIN_SDK:-18}
android_target_sdk=${DIPLAY_TARGET_SDK:-$android_min_sdk}
case "$android_min_sdk:$android_target_sdk" in
    18:18) apk_filename=DiPlay-Android43-Wireless-Experimental.apk ;;
    21:21) apk_filename=DiPlay-Android50-Wireless-Experimental.apk ;;
    *) echo 'Supported SDK variants: 18:18 or 21:21' >&2; exit 1 ;;
esac
build_dir="$project_dir/build"
delivery_dir="$workspace_dir/adaptation/output"
kotlin_dir=${DIPLAY_KOTLIN_DIR:-"$workspace_dir/tools/kotlinc"}
dependencies=${DIPLAY_DEPS_DIR:-"$workspace_dir/downloads"}
mkdir -p "$build_dir/dex" "$delivery_dir"
mkdir -p "$build_dir/java" "$build_dir/build-tools"
python3 "$project_dir/prepare_sources.py"
sh "$project_dir/build-native.sh"
python3 - "$project_dir" "$workspace_dir" <<'PY'
import os,sys,shutil
from pathlib import Path
project,workspace=map(Path,sys.argv[1:])
assets=project/'build/assets'
if assets.exists(): shutil.rmtree(assets)
shutil.copytree(project/'assets',assets)
shutil.copy(workspace/'adaptation/probe/assets/h264-test.mp4',assets/'h264-test.mp4')
explicit=os.environ.get('DIPLAY_AUTH_ASSETS_DIR')
if explicit:
    source=Path(explicit)/'offline-mfi'
    target=assets/'offline-mfi'
    target.mkdir()
    for name in ['identity.pk8','certificate.p7b']:
        original=source/name
        if not original.is_file() or not 0<original.stat().st_size<=16384: raise SystemExit('Incomplete runtime authentication input')
        shutil.copy(original,target/name)
    print('Runtime authentication selected explicitly')
else: print('Source-only build: runtime authentication omitted')
upload_config=os.environ.get('DIPLAY_LOG_UPLOAD_CONFIG')
if upload_config:
    source=Path(upload_config)
    if not source.is_file() or source.stat().st_size>4096: raise SystemExit('Invalid explicit upload configuration')
    folder=assets/'log-upload';folder.mkdir()
    shutil.copy(source,folder/'config.properties')
    print('Private upload configuration selected explicitly')
else: print('Source-only build: upload credential omitted')

PY
compiler_cp="$platform_jar:$dependencies/bcprov-jdk15to18-1.79.jar:$dependencies/bctls-jdk15to18-1.79.jar:$dependencies/bcutil-jdk15to18-1.79.jar:$dependencies/jmdns-3.5.9.jar:$dependencies/slf4j-api-1.7.36.jar"
"$jdk_dir/bin/javac" -source 8 -target 8 -bootclasspath "$platform_jar" -d "$build_dir/java" \
    "$project_dir/src/local/airuize/receiver/LegacyCharsetFields.java"
"$jdk_dir/bin/jar" cf "$build_dir/java.jar" -C "$build_dir/java" .
"$jdk_dir/bin/javac" -classpath "$kotlin_dir/lib/kotlin-compiler.jar" -d "$build_dir/build-tools" "$project_dir/build-tools/PatchJmDns.java"
"$jdk_dir/bin/java" -classpath "$build_dir/build-tools:$kotlin_dir/lib/kotlin-compiler.jar" PatchJmDns \
    "$dependencies/jmdns-3.5.9.jar" "$build_dir/jmdns-legacy.jar"
"$jdk_dir/bin/java" -Xmx2g -cp "$kotlin_dir/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$kotlin_dir" -no-jdk -jvm-target 1.8 -classpath "$compiler_cp" \
    "$build_dir/upstream" "$project_dir/src" -d "$build_dir/receiver.jar"
python3 - "$project_dir/AndroidManifest.xml" "$build_dir/AndroidManifest.xml" "$android_min_sdk" "$android_target_sdk" <<'PYSDK'
from pathlib import Path
import sys,xml.etree.ElementTree as ET
source,target,minimum,requested=sys.argv[1:]
namespace='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',namespace)
tree=ET.parse(source); sdk=tree.getroot().find('uses-sdk')
sdk.set('{'+namespace+'}minSdkVersion',minimum)
sdk.set('{'+namespace+'}targetSdkVersion',requested)
tree.write(target,encoding='utf-8',xml_declaration=True)
PYSDK
"$build_tools/aapt" package -f -M "$build_dir/AndroidManifest.xml" -S "$project_dir/res" \
    -A "$build_dir/assets" -I "$platform_jar" -0 mp4 -0 m4a -F "$build_dir/resources.apk"
"$jdk_dir/bin/java" -Xmx2g -cp "$build_tools/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 18 \
    --pg-conf "$project_dir/shrink.pro" --lib "$platform_jar" --output "$build_dir/dex" "$build_dir/receiver.jar" "$build_dir/java.jar" \
    "$kotlin_dir/lib/kotlin-stdlib.jar" "$dependencies/bcprov-jdk15to18-1.79.jar" "$dependencies/bctls-jdk15to18-1.79.jar" "$dependencies/bcutil-jdk15to18-1.79.jar" \
    "$build_dir/jmdns-legacy.jar" "$dependencies/slf4j-api-1.7.36.jar" "$dependencies/slf4j-nop-1.7.36.jar"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -u "$build_dir/unsigned.apk" classes*.dex)
(cd "$build_dir/native" && zip -q -u "$build_dir/unsigned.apk" lib/armeabi-v7a/libdiplay_usb.so)
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
if [ ! -f "$build_dir/receiver.keystore" ]; then
    "$jdk_dir/bin/keytool" -genkeypair -keystore "$build_dir/receiver.keystore" -storepass local-test-only \
        -keypass local-test-only -alias receiver -dname 'CN=Local DiPlay Legacy Test' -keyalg RSA \
        -keysize 2048 -validity 3650 -storetype PKCS12
fi
"$jdk_dir/bin/java" -jar "$build_tools/lib/apksigner.jar" sign --ks "$build_dir/receiver.keystore" \
    --ks-key-alias receiver --ks-pass pass:local-test-only --key-pass pass:local-test-only \
    --min-sdk-version "$android_min_sdk" --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --v4-signing-enabled false --out "$delivery_dir/$apk_filename" "$build_dir/aligned.apk"
"$jdk_dir/bin/java" -jar "$build_tools/lib/apksigner.jar" verify --verbose --min-sdk-version "$android_min_sdk" \
    "$delivery_dir/$apk_filename"
"$build_tools/aapt" dump badging "$delivery_dir/$apk_filename"
shasum -a 256 "$delivery_dir/$apk_filename"
