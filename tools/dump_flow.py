# -*- coding: utf-8 -*-
"""
抓包时序工具: 按时间戳交错输出指定 TCP 流的 C→S / S→C 解码包,
用于对齐「请求→回应」配对关系。

用法: python tools/dump_flow.py cap/login.pcap 2
"""
import os
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import TSHARK, dec_s2c, dec_c2s, RECV_BY, SEND_BY, ssc_dec, crc8_pingu
import datetime


def get_segments(pcap, want_stream):
    """返回 {stream: {"C": [(seq, ts, data)], "S": [...]}}"""
    out = subprocess.run([TSHARK, "-r", pcap, "-Y", "tcp.len>0", "-T", "fields",
                          "-e", "tcp.stream", "-e", "ip.src", "-e", "tcp.seq",
                          "-e", "frame.time_epoch", "-e", "tcp.payload",
                          "-E", "separator=|"],
                         capture_output=True, text=True).stdout
    streams = {}
    for line in out.splitlines():
        p = line.split("|")
        if len(p) < 5 or not p[4]:
            continue
        sid, src, seq, ts, pay = p[:5]
        if int(sid) != int(want_stream):
            continue
        drc = "S" if src == "66.135.19.7" else "C"
        streams.setdefault((sid, drc), []).append(
            (int(seq), float(ts), bytes.fromhex(pay.replace(":", ""))))
    return streams


def reassemble(segs):
    segs = sorted(segs)
    data, tsmap, off = b"", {}, 0
    for _, ts, d in segs:
        data += d
        for _ in range(len(d)):
            tsmap[off] = ts
            off += 1
    return data, tsmap


def decode_c2s_ts(data, tsmap):
    pkts, seq, pos, n = [], 40, 0, len(data)
    while pos < n:
        hc = (231 ^ (192 + seq)) & 0xFF
        if data[pos] != hc or pos + 5 > n:
            pos += 1
            continue
        ln = data[pos + 3] ^ 0x69
        if pos + 4 + ln + 1 > n:
            pos += 1
            continue
        payload = ssc_dec(seq, data[pos + 4:pos + 4 + ln])
        ok = crc8_pingu(seq & 0xFF, payload) == data[pos + 4 + ln]
        op = struct.unpack(">H", payload[:2])[0] ^ 0xA569 if len(payload) >= 2 else -1
        pkts.append({"ts": tsmap[pos], "seq": seq, "op": op, "ln": ln,
                     "p": payload, "crc_ok": ok})
        pos += 4 + ln + 1
        seq = (seq + 3) & 0xFFFFFFFF
    return pkts


def decode_s2c_ts(data, tsmap):
    pkts = []
    for start_seq in (40, 43):
        seq, pos, n, out = start_seq, 0, len(data), []
        while pos < n:
            hc = (231 ^ (102 + seq)) & 0xFF
            if data[pos] != hc or pos + 6 > n:
                pos += 1
                continue
            ln = struct.unpack_from(">I", data, pos + 2)[0] ^ 0x96CA5395
            if not (0 < ln < 200000) or pos + 6 + ln + 1 > n:
                pos += 1
                continue
            payload = ssc_dec(seq, data[pos + 6:pos + 6 + ln])
            ok = crc8_pingu(seq & 0xFF, payload) == data[pos + 6 + ln]
            op = struct.unpack(">H", payload[:2])[0] ^ 0xA569 if len(payload) >= 2 else -1
            out.append({"ts": tsmap[pos], "seq": seq, "op": op, "ln": ln,
                        "p": payload, "crc_ok": ok})
            pos += 6 + ln + 1
            seq = (seq + 3) & 0xFFFFFFFF
        if len(out) > len(pkts):
            pkts = out
    return pkts


def fmt_ts(ts, t0):
    return f"+{ts - t0:7.3f}s"


def main():
    pcap, sid = sys.argv[1], int(sys.argv[2])
    streams = get_segments(pcap, sid)
    events = []
    t0 = min(ts for segs in streams.values() for _, ts, _ in segs)
    for (s, drc), segs in streams.items():
        data, tsmap = reassemble(segs)
        if drc == "S" and data[:1] == b"\x00" and len(data) > 3:
            blen = struct.unpack_from(">H", data, 1)[0]
            events.append((tsmap[0], "S", "BANNER", blen, data[3:3 + blen], True))
            data = data  # banner 留在流里, dec_s2c 会跳过
        pkts = decode_s2c_ts(data, tsmap) if drc == "S" else decode_c2s_ts(data, tsmap)
        for p in pkts:
            name = (SEND_BY if drc == "S" else RECV_BY).get(p["op"], "??")
            events.append((p["ts"], drc, p["op"], p["ln"], p["p"], p["crc_ok"]))
    events.sort(key=lambda e: e[0])
    last = {"C": None, "S": None}
    for ts, drc, op, ln, payload, ok in events:
        mark = "" if ok else "  <<CRC失败"
        gap = ""
        name = "BANNER" if op == "BANNER" else (SEND_BY if drc == "S" else RECV_BY).get(op, "??")
        if op != "BANNER":
            same = last[drc] == op
            last[drc] = op
        hx = payload[:32].hex(' ')
        asc = ''.join(chr(c) if 32 <= c < 127 else '.' for c in payload[:32])
        print(f"{fmt_ts(ts, t0)} {drc}->S op={op:>3} {name:<22} ln={ln:>5}{mark} {hx} |{asc}|")


if __name__ == "__main__":
    main()
