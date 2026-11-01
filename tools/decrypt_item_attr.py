# -*- coding: utf-8 -*-
import struct
import re

def decrypt_idd_file(cipher, key):
    n_dwords = len(cipher) // 4
    out = bytearray(len(cipher))
    c0 = struct.unpack('<I', cipher[:4])[0]
    p0 = (c0 ^ key ^ 0x9c6c95ce) & 0xffffffff
    struct.pack_into('<I', out, 0, p0)
    prev = p0
    for i in range(1, n_dwords):
        ci = struct.unpack('<I', cipher[i*4:i*4+4])[0]
        esi = (i + 0x3C6) & 0xffffffff
        ebp = (esi + 0x41E) & 0xffffffff
        ebp = (ebp * esi) & 0xffffffff
        ebp = (ebp * i) & 0xffffffff
        ebp = (ebp * i) & 0xffffffff
        ebp = (ebp ^ ci ^ prev ^ 0x9c6c95ce) & 0xffffffff
        struct.pack_into('<I', out, i*4, ebp)
        prev = ebp
    return bytes(out)

with open(r'F:\Games\PopTag\fx.idd', 'rb') as f:
    f.seek(0x1925)
    data = f.read(10567)

# CRC = 2932426048 (0xAECA2140), val4 = 2413646538 (0x8FDD52CA)
for k in [2932426048, 2413646538]:
    dec = decrypt_idd_file(data, k)
    matches = list(re.finditer(b'[A-Za-z0-9_/]{4,}', dec))
    print(f"Key {k} (0x{k:08X}): found {len(matches)} strings")
    if len(matches) > 10:
        with open('tools/ItemAttr.bin', 'wb') as fp:
            fp.write(dec)
        for m in matches[:30]:
            print(f"  0x{m.start():04x}: {m.group().decode('latin1')}")
