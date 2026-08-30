# -*- coding: utf-8 -*-
"""
EN 登录连接 (login.pcap stream 2) 推送载荷提取工具。

提取 op 4 AskFirstInfoResult / op 11 ChannelsExtraInfo / op 13 ChannelsState / op 12 Unk12
的完整明文到 cap/login_s2c_op*.bin, 并生成 Kotlin 资料块 hex (stdout)。

时序 (tools/dump_flow.py cap/login.pcap 2):
  C op 2 AskFirstInfo  → S [4]                      (65B)
  C op 55              → S [11, 13, 12]             (218B/44B/27B, 频道列表)
  C op 11 進頻道       → S [11, 13]
op11 的 SSD 资料块内每个频道带一个 IPv4 (66.135.19.7), 由 --ip 参数改写 (默认 127.0.0.1)。

用法: python tools/extract_login_flow.py [--pcap cap/login.pcap] [--ip 127.0.0.1]
"""
import argparse
import os
import socket
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import get_streams_full, dec_s2c
from extract_first_push import ssd_decode, ssd_encode

OLD_IP = socket.inet_aton("66.135.19.7")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "cap", "login.pcap"))
    ap.add_argument("--ip", default="127.0.0.1")
    ap.add_argument("--stream", type=int, default=2)
    ap.add_argument("--outdir", default=None)
    args = ap.parse_args()

    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = args.outdir or os.path.join(base, "cap")

    streams = get_streams_full(args.pcap)
    sc = next(s["data"] for (sid, drc), s in streams.items() if sid == args.stream and drc == "S")
    found = {}
    for p in dec_s2c(sc):
        op = struct.unpack(">H", p["p"][:2])[0] ^ 0xA569
        if op in (4, 11, 13, 12) and op not in found:
            found[op] = p["p"]
            open(os.path.join(outdir, f"login_s2c_op{op}.bin"), "wb").write(p["p"])
            print(f"op{op:3d} ln={p['ln']:4d} sq={p['seq']} -> cap/login_s2c_op{op}.bin")

    # op11: SSD 块内 IP 改写
    body11 = found[11][2:]
    blob11 = body11[5:]  # Encode1Bool(true) + Encode4(211) 之后
    plain = ssd_decode(blob11)
    n = plain.count(OLD_IP)
    plain_new = plain.replace(OLD_IP, socket.inet_aton(args.ip))
    enc_new = ssd_encode(plain_new)
    assert ssd_decode(enc_new) == plain_new and plain_new.count(socket.inet_aton(args.ip)) == n
    print(f"\nop11 SSD 块内 {n} 处 IP 已改写为 {args.ip}, 重编码自校验 OK")

    print("\n--- Kotlin 常量 ---")
    print(f"EN_ASK_FIRST_INFO_RESULT_BLOB ({len(found[4])-2}B) = \"{found[4][2:].hex()}\"")
    print(f"EN_CHANNELS_EXTRA_BLOB ({len(enc_new)}B) = \"{enc_new.hex()}\"")
    print(f"EN_CHANNELS_STATE_BLOB ({len(found[13])-6}B) = \"{found[13][6:].hex()}\"")
    print(f"EN_UNK12_BLOB ({len(found[12])-6}B) = \"{found[12][6:].hex()}\"")


if __name__ == "__main__":
    main()
