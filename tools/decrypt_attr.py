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
    f.seek(0x1205)
    data = f.read(1824)

# Try various keys
keys = [
    967330562,
    2320992851,
    0,
    0x05B201FC,
    0x719CAB30,
]

for k in keys:
    dec = decrypt_idd_file(data, k)
    slen = struct.unpack('<I', dec[:4])[0]
    print(f'Key {k} (0x{k:08X}): slen={slen}')
    if 0 < slen <= 52:
        print('  VALID SLEN!', dec[4:4+slen])

# What if key makes slen == 3 ('Dao') or 4 ('bazzi' or 'kephi')?
# slen is dec[0] = c0 ^ key ^ 0x9c6c95ce
# If slen is 3: key = c0 ^ 0x9c6c95ce ^ 3
c0 = struct.unpack('<I', data[:4])[0]
for test_len in [3, 4, 5, 6, 7, 8]:
    k = c0 ^ 0x9c6c95ce ^ test_len
    dec = decrypt_idd_file(data, k)
    s = dec[4:4+test_len]
    print(f'Test len {test_len} (key=0x{k:08X}): s={s}')
