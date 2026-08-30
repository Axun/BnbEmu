#!/usr/bin/env python3
"""提取 47611 passport (nmco_session) TCP 流的原始字节, 按 C→S / S→C 分方向 dump。

用法: python tools/extract_passport.py <pcap> <stream> [...]
输出: cap/passport_<pcap名>_s<stream>_c2s.bin / _s2c.bin 及十六进制/UTF-16 预览
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import TSHARK


def get_segments(pcap, want_stream):
    out = subprocess.run([TSHARK, "-r", pcap, "-Y", "tcp.len>0", "-T", "fields",
                          "-e", "tcp.stream", "-e", "ip.src", "-e", "tcp.seq",
                          "-e", "tcp.payload", "-E", "separator=|"],
                         capture_output=True, text=True).stdout
    c, s = [], []
    for line in out.splitlines():
        p = line.split("|")
        if len(p) < 4 or not p[3]:
            continue
        sid, src, seq, pay = p[0], p[1], p[2], p[3]
        if int(sid) != int(want_stream):
            continue
        data = bytes.fromhex(pay.replace(":", ""))
        (s if src == "66.135.19.7" else c).append((int(seq), data))
    c.sort()
    s.sort()
    return b"".join(d for _, d in c), b"".join(d for _, d in s)


def preview(tag, data):
    print(f"--- {tag} ({len(data)}B)")
    print(" hex:", data.hex(" "))
    txt = data.decode("utf-16-le", errors="replace")
    printable = "".join(ch if 0x20 <= ord(ch) < 0x7F else "." for ch in txt)
    print(f" u16: {printable}")
    ascii_txt = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in data)
    print(f" asc: {ascii_txt}")


def main():
    os.makedirs("cap", exist_ok=True)
    for pcap in sys.argv[1:]:
        streams = subprocess.run([TSHARK, "-r", pcap, "-Y", "tcp.port==47611",
                                  "-T", "fields", "-e", "tcp.stream"],
                                 capture_output=True, text=True).stdout.split()
        base = os.path.splitext(os.path.basename(pcap))[0]
        for sid in sorted(set(int(x) for x in streams)):
            c2s, s2c = get_segments(pcap, sid)
            if not c2s and not s2c:
                continue
            for tag, data in (("c2s", c2s), ("s2c", s2c)):
                fn = f"cap/passport_{base}_s{sid}_{tag}.bin"
                open(fn, "wb").write(data)
                preview(fn, data)
            print()


if __name__ == "__main__":
    main()
