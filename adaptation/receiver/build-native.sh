#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
# Official Zig 0.13.0 macOS aarch64 archive, verified before extraction.
zig_bin=${DIPLAY_ZIG:-"$workspace_dir/tools/zig-macos-aarch64-0.13.0/zig"}
output_dir="$project_dir/build/native/lib/armeabi-v7a"
mkdir -p "$output_dir"
jni_dir="$project_dir/build/native-jni"
mkdir -p "$jni_dir"
python3 - "$jni_dir" "${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}" <<'PYJNI'
from pathlib import Path
import sys
original=Path(sys.argv[2])/'include'
out=Path(sys.argv[1])
text=(original/'jni.h').read_text()
# jni.h does not use stdio; removing this unused include permits freestanding compilation.
assert '#include <stdio.h>' in text
(out/'jni.h').write_text(text.replace('#include <stdio.h>',''))
(out/'jni_md.h').write_bytes((original/('darwin' if sys.platform=='darwin' else 'linux')/'jni_md.h').read_bytes())
PYJNI
ZIG_GLOBAL_CACHE_DIR="$project_dir/build/zig-cache" ZIG_LOCAL_CACHE_DIR="$project_dir/build/zig-local-cache" "$zig_bin" cc \
    -target arm-linux-musleabi -mcpu=cortex_a9 -marm -mfloat-abi=softfp \
    -std=c11 -O3 -ffunction-sections -fdata-sections -fno-builtin -fPIC -fvisibility=hidden -fno-stack-protector \
    -fno-unwind-tables -fno-asynchronous-unwind-tables -nostdlib -shared \
    -Wl,-soname,libdiplay_usb.so -Wl,--hash-style=sysv -Wl,-z,noexecstack \
    -Wl,--version-script,"$project_dir/native/exports.map" -Wl,--strip-all -Wl,--gc-sections -Wl,-z,relro -Wl,-z,now -Wl,--build-id=none -Wl,--no-undefined \
    -I"$jni_dir" \
    "$project_dir/native/usb_interface.c" "$project_dir/native/audio_crypto.c" \
    "$project_dir/native/vendor/monocypher/monocypher.c" "$project_dir/native/freestanding_memory.c" -o "$output_dir/libdiplay_usb.so"
python3 "$project_dir/verify_native.py" "$output_dir/libdiplay_usb.so"
