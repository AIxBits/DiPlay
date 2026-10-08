#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
sdk_dir=${DIPLAY_PROBE_SDK_DIR:-"$workspace_dir/tools/android"}
build_dir="$project_dir/build"
build_tools=${DIPLAY_BUILD_TOOLS_DIR:-"$sdk_dir/android-14"}
platform_jar=${DIPLAY_PLATFORM_JAR:-"$sdk_dir/android-4.3.1/android.jar"}
kotlin_dir=${DIPLAY_KOTLIN_DIR:-"$workspace_dir/tools/kotlinc"}
deps=${DIPLAY_DEPS_DIR:-"$workspace_dir/downloads"}
"$jdk_dir/bin/java" -Xmx2g -cp "$build_tools/lib/d8.jar" com.android.tools.r8.R8 \
    --classfile --release --pg-conf "$project_dir/shrink.pro" --lib "$platform_jar" \
    --output "$build_dir/packaging-test-raw.jar" "$build_dir/receiver.jar" "$build_dir/java.jar" \
    "$kotlin_dir/lib/kotlin-stdlib.jar" "$deps/bcprov-jdk15to18-1.79.jar" "$deps/bctls-jdk15to18-1.79.jar" "$deps/bcutil-jdk15to18-1.79.jar" \
    "$build_dir/jmdns-legacy.jar" "$deps/slf4j-api-1.7.36.jar" "$deps/slf4j-nop-1.7.36.jar"
# R8 copies stale dependency signatures/indexes; remove them from the JVM-only test archive.
python3 - "$build_dir" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
build=Path(sys.argv[1])
with ZipFile(build/'packaging-test-raw.jar') as source, ZipFile(build/'packaging-test.jar','w') as target:
    for name in source.namelist():
        if name == 'META-INF/INDEX.LIST' or (name.startswith('META-INF/') and name.endswith(('.SF','.RSA','.DSA'))): continue
        target.writestr(name, source.read(name))
PY
"$jdk_dir/bin/javac" -source 8 -target 8 -cp "$deps/jmdns-3.5.9.jar" -d "$build_dir/build-tools" "$project_dir/build-tools/CheckMdnsStartup.java"
"$jdk_dir/bin/java" -cp "$build_dir/build-tools:$build_dir/packaging-test.jar" CheckMdnsStartup
# Exercise the packaged lightweight TLS client against an independent JDK TLS1.2 peer.
mkdir -p "$build_dir/tls-test-identity"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$build_dir/tls-test-identity/key.pem" > /dev/null 2>&1
openssl req -new -x509 -key "$build_dir/tls-test-identity/key.pem" \
    -out "$build_dir/tls-test-identity/cert.pem" -subj '/CN=Synthetic USB TLS Test' -days 1 > /dev/null 2>&1
"$jdk_dir/bin/javac" -source 8 -target 8 -cp "$build_dir/packaging-test.jar" -d "$build_dir/build-tools" "$project_dir/build-tools/CheckLockdownTls.java"
"$jdk_dir/bin/java" -cp "$build_dir/build-tools:$build_dir/packaging-test.jar" CheckLockdownTls \
    "$build_dir/tls-test-identity/key.pem" "$build_dir/tls-test-identity/cert.pem"
