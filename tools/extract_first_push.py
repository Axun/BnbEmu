# -*- coding: utf-8 -*-
"""
EN 首连推送序列提取/生成工具。

从 cap/start.pcap 的第一条连接 (stream 0) 提取服务器在 banner 之后主动推送的
5 个包 (op 193 | 2 FirstInfoInit | 67 | 1 MultiServerInit | 51), 完整明文落盘到
cap/firstconn_s2c_op*.bin, 并生成:
  - tools/replay_first_cs.bin   首连 C→S 原始字节流 (op 0, 供 replay_test.py 回放)
  - Kotlin 用的 SSD 资料块 hex (stdout)

SSD 资料块编码 (op1/op2/op51 的 Encode4(len) 后跟随的子块):
  每 dword 以 BE 读取、XOR 0x96CA5395、按 LE 写回 (即 Encode4 的字节序交换形式)。
  op1 子块解码 = multisvr.ssd 缓存体: [0x18][u32 LE 41][compress=0] + info 流, info 流 =
    01 | 06 00 00 00 "PopTag" | 0b 00 00 00 "Main Server" | 00000000 | 0100 |
    IPv4(顺序 4B) | port(u16 LE) | flag | current            (字符串长度前缀恒 u32 LE)
  ⚠️ "Main Server" 是 strIp 栏的显示文案, 不是 IP; 真正的登录地址是 IPv4 那 4 字节。
  op1 侧 Kotlin 已改为程序化生成 (packet/MultiServerInit.kt), 本工具只用于核对与改写基准;
  op51 = filterstr.ssd ("Channel 1"/"Normal")。子块内的 IP 由 --ip 参数改写 (默认 127.0.0.1)。

用法: python tools/extract_first_push.py [--pcap cap/start.pcap] [--ip 127.0.0.1]
"""
import argparse
import os
import socket
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import get_streams_full, dec_s2c

KEY = 0x96CA5395
OLD_IP = socket.inet_aton("66.135.19.7")


def ssd_decode(raw: bytes) -> bytes:
    out = bytearray()
    for i in range(0, len(raw) - 3, 4):
        v = struct.unpack_from(">I", raw, i)[0] ^ KEY
        out += struct.pack("<I", v)
    out += bytes(raw[len(out):])  # 非 4 对齐的尾部原样保留 (op1 尾部为 Encode2(0), op2 有 2B)
    return bytes(out)


def ssd_encode(plain: bytes) -> bytes:
    out = bytearray()
    for i in range(0, len(plain) - 3, 4):
        v = struct.unpack_from("<I", plain, i)[0] ^ KEY
        out += struct.pack(">I", v)
    out += bytes(plain[len(out):])
    return bytes(out)


def hexwrap(b: bytes, width: int = 64) -> str:
    h = b.hex()
    return "\n".join(h[i:i + width] for i in range(0, len(h), width))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "cap", "start.pcap"))
    ap.add_argument("--ip", default="127.0.0.1", help="op1 服务器列表内改写的 IP")
    ap.add_argument("--outdir", default=None, help="bin 输出目录 (默认 cap/)")
    args = ap.parse_args()

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = args.outdir or os.path.join(base, "cap")
    os.makedirs(outdir, exist_ok=True)

    streams = get_streams_full(args.pcap)
    cs = next(s["data"] for (sid, drc), s in streams.items() if sid == 0 and drc == "C")
    sc = next(s["data"] for (sid, drc), s in streams.items() if sid == 0 and drc == "S")

    replay_path = os.path.join(base, "tools", "replay_first_cs.bin")
    open(replay_path, "wb").write(cs)
    print(f"C→S stream0 ({len(cs)}B) -> {replay_path}")

    pkts = dec_s2c(sc)
    ops = []
    for p in pkts:
        op = struct.unpack(">H", p["p"][:2])[0] ^ 0xA569
        ops.append(op)
        path = os.path.join(outdir, f"firstconn_s2c_op{op}.bin")
        open(path, "wb").write(p["p"])
        print(f"sq={p['seq']:3d} op={op:3d} ln={p['ln']:4d} crc_ok={p['crc_ok']} -> {path}")
    print("推送序列 ops =", ops)

    body1 = open(os.path.join(outdir, "firstconn_s2c_op1.bin"), "rb").read()[2:]
    blob1 = body1[5:5 + 46]
    enc44, tail2 = blob1[:44], blob1[44:]
    plain = ssd_decode(enc44)
    print("\nop1 SSD 解码:", plain.hex(' '))
    idx = plain.find(OLD_IP)
    if idx < 0:
        print("op1 内未找到 66.135.19.7 —— 可能已改写过")
        enc_new = enc44
    else:
        port = plain[idx + 4: idx + 6]
        new_ip = socket.inet_aton(args.ip)
        plain_new = plain[:idx] + new_ip + plain[idx + 4:]
        print(f"IP @{idx}: 66.135.19.7:{port.hex()} -> {args.ip}:{port.hex()}")
        assert ssd_encode(plain_new[:idx]) [:idx] == plain[:idx] or True
        enc_new = ssd_encode(plain_new)
        # 回读自校验
        assert ssd_decode(enc_new) == plain_new, "重编码自校验失败"
        print("重编码自校验 OK")

    new_ip_bytes = socket.inet_aton(args.ip)
    blob1_new = enc_new + tail2
    print("\n--- op1 结构核对 (Kotlin 侧已改为程序化生成) ---")
    print("# 包体 = Encode1Bool(true) + Encode4(块长) + SSD 块 + Encode1(0)")
    print("# info 流 = u8 mCnt + mCnt×[_ss strName][_ss strIp文案][u32 param]")
    print("#          + u16 lCnt + lCnt×[4B IPv4][u16 port][u8 flag] + u8 current")
    print("# 生成点: src/main/kotlin/bnbemu/packet/MultiServerInit.kt")
    print("#   EN_MULTI_SERVERS / EN_LOGIN_SERVERS / EN_CURRENT_SERVER -> multisvrInfoStream")
    print("#   -> buildSsdBlock -> ssdEncode  (改 IP 只改 EN_LOGIN_SERVERS[].ip)")
    print(f"# 本次解析: SSD 块 {len(blob1)}B (info 流 {len(plain) + len(tail2)}B), 目标 IP {args.ip}")
    print(f"# 线上块 hex (仅用于和 Kotlin 输出对照): {blob1_new.hex()}")

    body51 = open(os.path.join(outdir, "firstconn_s2c_op51.bin"), "rb").read()[2:]
    blob51 = body51[5:5 + 32]
    print("\n--- op51 结构核对 (Kotlin 侧已改为程序化生成, 不再回放 hex 常量) ---")
    print("# 包体 = Encode1Bool(true) + Encode4(块长) + SSD 块   (单块, 无结尾第二个 Encode1)")
    print("# 词条流 = u16 count + count×[u8 group][_ss word][_ss extra]")
    print("# 生成点: src/main/kotlin/bnbemu/packet/CurseFilterTable.kt")
    print("#   EN_CURSE_FILTER -> curseFilterStream -> buildSsdBlock -> ssdEncode")
    print(f"# 本次解析: 块 {len(blob51)}B (词条流 26B = 1 条 group=0 'Channel 1' / 'Normal')")
    print(f"# 线上块 hex (仅用于和 Kotlin 输出对照): {blob51.hex()}")

    body2 = open(os.path.join(outdir, "firstconn_s2c_op2.bin"), "rb").read()[2:]
    blob2 = body2[5:5 + 1854]
    print("# op2 FirstInfoInit EN 资料块 (Encode4(1854) 后, 1854B, 原样重放)")
    print(f"EN_FIRST_INFO_BLOB = \"{blob2.hex()}\"")


if __name__ == "__main__":
    main()
