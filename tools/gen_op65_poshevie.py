# -*- coding: utf-8 -*-
"""生成注入了 Posh Evie (蓝妹妹 / LuxuryMarid, ID 10) 角色使用权道具的 op 65 商城目录包。

客户端判定逻辑 (CA.exe: 0x006646AE CStockContainer::FindCharacterStock(4)):
1. 遍历玩家拥有的物品栏 (由 op 65 初始化的 CStockContainer (0x994d08 + 0x34))
2. 校验 Category == 0x10 (角色道具卡, 16)
3. 校验 Count == 1 (数量/启用状态)
4. 校验 ItemDef->TargetCharID == 4 (Marid / 蓝蓝)

本脚本解密 op 65 SSD 数据块，注入一条指向 TargetCharID == 4 的角色道具记录，
重新打包并计算 SSD 长度，生成 cap/chan_s2c_op65_poshevie.bin。
"""
import os
import struct

def main():
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_bin = os.path.join(base, "cap/chan_s2c_op65_50550B_0.bin")
    dst_bin = os.path.join(base, "cap/chan_s2c_op65_poshevie.bin")

    with open(src_bin, "rb") as f:
        raw = f.read()

    # 前 2 字节为 opcode 65 (Encode2(65) = a5 28)
    opcode = raw[:2]
    enc_blob = raw[2:]

    # 解密 SSD 数据块 (首 4 字节为 block_len)
    body = enc_blob[4:]
    dec = bytearray(len(body))
    for i in range(0, len(body) - 3, 4):
        dw = struct.unpack(">I", body[i:i+4])[0] ^ 0x96CA5395
        struct.pack_into("<I", dec, i, dw)

    # Header 格式: [0x18][block_len:2 LE][0x00, 0x00, 0x00][count:2 LE]
    orig_count = struct.unpack("<H", dec[6:8])[0]
    print(f"Original item count: {orig_count}")

    # 构造 Posh Evie 角色使用权道具卡:
    # prod_id = 2082001 (唯一商城商品编号)
    # cat = 0x10 (Category 16: 角色卡 / 升级卡)
    # name = "Posh Evie"
    # p50 = 1
    # item_id = 2082 (指向 Unk60 中 TargetCharID == 4 的 ItemDef)
    # cnt = 1 (Count >= 1 满足 FindCharacterStock 校验)
    # desc = "Item/CharDeco"
    prod_id = 2082001
    cat = 0x10
    name = b"Posh Evie"
    p50 = 1
    item_id = 2082
    cnt = 1
    desc = b"Item/CharDeco"
    tail = struct.pack("<II", 0, 0) + bytes(27) + b"\x01" # 36 字节尾部

    new_item = bytearray()
    new_item += struct.pack("<I", prod_id)
    new_item += struct.pack("<B", cat)
    new_item += struct.pack("<I", len(name))
    new_item += name
    new_item += struct.pack("<H", p50)
    new_item += struct.pack("<I", item_id)
    new_item += struct.pack("<I", cnt)
    new_item += struct.pack("<I", len(desc))
    new_item += desc
    new_item += tail

    new_dec = bytearray(dec) + new_item
    # 对齐 4 字节
    pad = (4 - (len(new_dec) % 4)) % 4
    if pad:
        new_dec += bytes(pad)

    # 更新条目数与 SSD 块长度
    new_count = orig_count + 1
    struct.pack_into("<H", new_dec, 6, new_count)
    block_len = len(new_dec) - 5
    struct.pack_into("<H", new_dec, 1, block_len & 0xFFFF)

    # 重新加密为 SSD 格式 (每 DWORD LE 读取 ^ 0x96CA5395 按 BE 写回)
    out = bytearray(opcode) + bytearray(4 + len(new_dec))
    struct.pack_into(">I", out, 2, len(new_dec) ^ 0x96CA5395)
    for i in range(0, len(new_dec), 4):
        dw = struct.unpack("<I", new_dec[i:i+4])[0] ^ 0x96CA5395
        struct.pack_into(">I", out, 6 + i, dw)

    with open(dst_bin, "wb") as f:
        f.write(out)

    print(f"Generated {dst_bin}: {len(out)} bytes, new count: {new_count}")

if __name__ == "__main__":
    main()
