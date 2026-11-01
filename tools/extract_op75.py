# -*- coding: utf-8 -*-
"""从 tst.pcap stream 4 (进练习房的主游戏连线) 提取:
  1) 完整 C→S 原始字节流 → tools/replay_practice_full_cs.bin
  2) 裁剪的 C→S 回放 → tools/replay_practice_cs.bin
     (op 7 OnConnEstablished 保留原 seq=40 帧; op 75 练习房请求载荷重封装至 seq=43,
      使回放时解码器能连续解出两帧)
  3) S→C 回应链各 op 的 body (去 2B opcode) → cap/practice_s2c_op<NN>_<len>B_<n>.bin (Kotlin blob 常量源)

用法: python tools/extract_op75.py
"""
import os
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import TSHARK, dec_s2c, dec_c2s, ssc_enc, crc8_pingu, SEND_BY

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCAP = os.path.join(BASE, "tst.pcap")
OUT = os.path.join(BASE, "cap")
REPLAY_FULL = os.path.join(BASE, "tools", "replay_practice_full_cs.bin")
REPLAY = os.path.join(BASE, "tools", "replay_practice_cs.bin")
SERVER_IP = "66.135.19.7"
CLIENT_IP = "192.168.50.2"


def get_stream(pcap, want_stream, want_src):
    out = subprocess.run([TSHARK, "-r", pcap, "-Y", "tcp.len>0", "-T", "fields",
                          "-e", "tcp.stream", "-e", "ip.src", "-e", "tcp.seq",
                          "-e", "tcp.payload", "-E", "separator=|"],
                         capture_output=True, text=True).stdout
    segs = []
    for line in out.splitlines():
        p = line.split("|")
        if len(p) < 4 or not p[3]:
            continue
        sid, src, seq, pay = p[0], p[1], p[2], p[3]
        if int(sid) != want_stream or src != want_src:
            continue
        segs.append((int(seq), bytes.fromhex(pay.replace(":", ""))))
    segs.sort()
    return b"".join(d for _, d in segs)


def build_c2s_frame(seq, payload, uid):
    """构造 C→S 加密帧: [hc][uid:2 LE^0xA569][len^0x69:1][ssc_enc(payload)][CRC8]"""
    enc = ssc_enc(seq, payload)
    hc = (231 ^ (192 + seq)) & 0xFF
    hdr = bytes([hc]) + struct.pack("<H", uid ^ 0xA569) + bytes([len(payload) ^ 0x69])
    crc = crc8_pingu(seq & 0xFF, payload)
    return hdr + enc + bytes([crc])


def main():
    os.makedirs(OUT, exist_ok=True)

    # 1) 完整 C→S 流
    c2s = get_stream(PCAP, 4, CLIENT_IP)
    with open(REPLAY_FULL, "wb") as f:
        f.write(c2s)
    print(f"完整 C→S {len(c2s)}B → {os.path.basename(REPLAY_FULL)}")

    # 2) 解码 C→S 帧, 提取 op 7 原帧 + op 75 / op 92 载荷
    frames = dec_c2s(c2s)
    op7_raw = None
    payloads = {}   # op -> (payload, uid)
    pos = 0
    for p in frames:
        flen = 4 + p["ln"] + 1
        raw = c2s[pos:pos + flen]
        pos += flen
        q = p["p"]
        op = struct.unpack(">H", q[:2])[0] ^ 0xA569 if len(q) >= 2 else -1
        if op == 7:
            op7_raw = raw
        elif op in (75, 92):
            payloads[op] = (q, p["uid"])
    assert op7_raw and 75 in payloads, "未找到 op 7 / op 75 帧"
    # op 7 占 seq=40, op 75 重封装到 seq=43, op 92 重封装到 seq=46
    sel = op7_raw
    sel += build_c2s_frame(43, payloads[75][0], payloads[75][1])
    if 92 in payloads:
        sel += build_c2s_frame(46, payloads[92][0], payloads[92][1])
    with open(REPLAY, "wb") as f:
        f.write(sel)
    print(f"裁剪回放 {len(sel)}B (op7@40 + op75@43 + {'op92@46' if 92 in payloads else ''}) → {os.path.basename(REPLAY)}")

    # 3) S→C 解码, 按 op 输出完整 payload (含 2B opcode, 与 chan_s2c_op* 基准同格式)
    s2c = get_stream(PCAP, 4, SERVER_IP)
    pkts = dec_s2c(s2c)
    print(f"S→C {len(pkts)} 包")
    counters = {}
    for p in pkts:
        q = p["p"]
        op = struct.unpack(">H", q[:2])[0] ^ 0xA569 if len(q) >= 2 else -1
        if op < 0:
            continue
        full = q
        n = counters.get(op, 0)
        counters[op] = n + 1
        fname = os.path.join(OUT, f"practice_s2c_op{op}_{len(full)}B_{n}.bin")
        with open(fname, "wb") as f:
            f.write(full)
    print("cap/practice_s2c_op* 已写出 (含 opcode, replay_test 用):")
    for op in sorted(counters):
        print(f"  op {op} ({SEND_BY.get(op, '??')}) × {counters[op]}")


if __name__ == "__main__":
    main()
