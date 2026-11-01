# -*- coding: utf-8 -*-
import struct

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

dec = decrypt_idd_file(data, 967330562)

# Parse according to CA.exe:
# 0x006B48A5:
#   name_len = [edi]
#   edi += 4
#   name = [edi : edi + name_len]
#   edi += name_len
#   char_id = [edi]
#   edi += 4
#   field1 = [edi]; edi += 4
#   field2 = [edi]; edi += 4
#   field3 = [edi]; edi += 4
#   field4 = [edi]; edi += 4
#   field5 = [edi]; edi += 4
#   field6 = [edi]; edi += 4
#   field7 = [edi]; edi += 4
#   field8 = [edi]; edi += 4
#   field9 = [edi]; edi += 4

edi = 0
idx = 0
while edi < len(dec) - 10:
    name_len = struct.unpack('<I', dec[edi:edi+4])[0]
    edi += 4
    if name_len == 0 or name_len > 64 or edi + name_len > len(dec):
        break
    name = dec[edi:edi+name_len].decode('latin1', 'ignore')
    edi += name_len
    
    char_id = struct.unpack('<I', dec[edi:edi+4])[0]
    edi += 4
    
    # 9 dword fields
    fields = struct.unpack('<9I', dec[edi:edi+36])
    edi += 36
    
    print(f'[{idx:2d}] Name: {name:<20} ID: {char_id:2d} | Fields: {fields}')
    idx += 1
