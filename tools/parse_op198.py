import struct

BLOB = bytes.fromhex('''5896ca53 9796ca53 945596ca 5381a56a c42d3588 95ae63d6 2cc49196 c9fa159a
96ca7483 5796ca53 94a565fb af27dcf3 be1abaf1 ab11f888 95ae63d6 2cc49196 c9fa15a5
96ca5397 96ca5394 5596ca53 81a56ac4 2d358895 ae63d62c c49196c9 fa159a96 ca748357
96ca5394 a565fbaf 27dcf3be 1abaf1ab 11f88895 ae63d62c c49196c9 fa15a5'''.replace(' ', '').replace('\n', ''))

print(f"Total BLOB len: {len(BLOB)}")
idx = 0
header_count = BLOB[idx] ^ 0x5A
idx += 1
print(f"Header count: {header_count}")

for g in range(header_count):
    item_count = struct.unpack('>I', BLOB[idx:idx+4])[0] ^ 0x96CA5395
    idx += 4
    print(f"\n--- Group {g} (itemCount={item_count}) ---")
    for it in range(item_count):
        st = idx
        # 0x0067269F: call 0x78f100 (Encode4)
        f1 = struct.unpack('>I', BLOB[idx:idx+4])[0] ^ 0x96CA5395
        idx += 4
        # 0x006726B1: call 0x78f030 (Encode1)
        f2 = BLOB[idx] ^ 0x5A
        idx += 1
        # 0x006726C6: call 0x78f100 (Encode4)
        f3 = struct.unpack('>I', BLOB[idx:idx+4])[0] ^ 0x96CA5395
        idx += 4
        # 0x006726DF: call 0x78fdf0 (String)
        # Let's check how string is read in 0x78fdf0!
        # In CA.exe, strings are usually Encode2(len) + bytes or similar
        slen = struct.unpack('>H', BLOB[idx:idx+2])[0] ^ 0xA569
        idx += 2
        sbytes = BLOB[idx:idx+slen]
        idx += slen
        # 0x006726EE: call 0x78f1c0 (8 bytes)
        b8 = BLOB[idx:idx+8]
        idx += 8
        # 0x00672716: call 0x78f100 (Encode4)
        f6 = struct.unpack('>I', BLOB[idx:idx+4])[0] ^ 0x96CA5395
        idx += 4
        # 0x00672729: call 0x78f030 (Encode1)
        f7 = BLOB[idx] ^ 0x5A
        idx += 1
        print(f"Item {it} (len {idx-st}): f1={f1} (0x{f1:08x}), f2={f2}, f3={f3}, str_len={slen}, str_hex={sbytes.hex()}, b8={b8.hex()}, f6={f6}, f7={f7}")

print(f"\nRemaining bytes: {len(BLOB) - idx} (idx={idx})")
