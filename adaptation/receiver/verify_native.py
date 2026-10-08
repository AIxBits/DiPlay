"""Audit the self-contained ARM EABI JNI library without a host ELF toolchain."""
import struct
import sys
from pathlib import Path

def verify(data):
    assert b'/Users/' not in data and b'/home/' not in data, 'Personal build paths must not be embedded'
    assert data[:7] == b'\x7fELF\x01\x01\x01', '32-bit little-endian ELF required'
    assert struct.unpack_from('<HH', data, 16) == (3, 40), 'ARM shared library required'
    flags = struct.unpack_from('<I', data, 36)[0]
    assert flags >> 24 == 5 and not flags & 0x400, 'ARM EABI5 soft-float calling convention required'
    shoff = struct.unpack_from('<I', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 46)
    sections = [struct.unpack_from('<10I', data, shoff+i*size) for i in range(count)]
    exported = []
    for section in sections:
        if section[1] == 11: # SHT_DYNSYM
            names = sections[section[6]]
            strings = data[names[4]:names[4]+names[5]]
            for offset in range(section[4]+16, section[4]+section[5], 16):
                name, value, length, info, other, index = struct.unpack_from('<IIIBBH', data, offset)
                assert index != 0, 'Native library must have no undefined imports'
                symbol = strings[name:strings.index(0, name)].decode()
                if info >> 4 in (1, 2): exported.append(symbol)
        if section[1] == 6: # SHT_DYNAMIC
            for offset in range(section[4], section[4]+section[5], 8):
                tag, value = struct.unpack_from('<II', data, offset)
                assert tag not in (1, 22), 'No DT_NEEDED or DT_TEXTREL permitted'
    assert sorted(exported) == sorted(['Java_local_airuize_receiver_LegacyUsbNative_setInterface', 'Java_local_airuize_receiver_LegacyAudioCrypto_openNative']), exported
    print('PASS: ARM EABI5 JNI library, two audited JNI exports, no libc imports/dependencies or text relocations')

if __name__ == '__main__': verify(Path(sys.argv[1]).read_bytes())
