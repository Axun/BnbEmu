# -*- coding: utf-8 -*-
"""
PopTag 真实服务器协议解码器 (从 cap/*.pcap 逆向, 经 CRC8 oracle 全量验证 188/188)

帧格式 (seq 两方向各自独立计数, 初始 40, 步进 3):
  S->C 握手(seq=40): [0x00][len:2 BE 明文][payload 明文]           (无 hc, 无 CRC)
  S->C 加密包:       [hc][0x00][len:4 BE ^ 0x96CA5395][payload][CRC8]
                     hc = (0+231) ^ (102+seq);  payload = simpleStreamEncrypt3(seq) 加密
                     CRC8 = CRC8.kt(key=seq&0xFF, 明文payload)
  C->S 加密包:       [hc][Encode2(uid) BE:2][len ^ 0x69:1][payload][CRC8]
                     hc = (0+231) ^ (192+seq);  uid = 每连接分配的会话 id
  payload 内容:      [Encode2(opcode) BE:2][业务字段...]   opcode = BE16 ^ 0xA569
  字段混淆:          Encode1 ^0x5A / Encode2 ^0xA569 / Encode4 ^0x96CA5395 (与项目一致)
"""
import subprocess, struct, yaml, os, sys
TSHARK = r"E:\Softwares\Wireshark\tshark.exe"
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECV = yaml.safe_load(open(os.path.join(BASE, "EN_0_recv.yaml"), encoding="utf-8"))
SEND = yaml.safe_load(open(os.path.join(BASE, "EN_0_send.yaml"), encoding="utf-8"))
RECV_BY = {v: k for k, v in RECV.items()}
SEND_BY = {v: k for k, v in SEND.items()}

def get_streams_full(pcap):
    out = subprocess.run([TSHARK, "-r", pcap, "-Y", "tcp.len>0", "-T", "fields",
                          "-e", "tcp.stream", "-e", "ip.src", "-e", "tcp.srcport",
                          "-e", "ip.dst", "-e", "tcp.dstport", "-e", "tcp.seq",
                          "-e", "tcp.payload", "-E", "separator=|"],
                         capture_output=True, text=True).stdout
    streams = {}
    for line in out.splitlines():
        p = line.split("|")
        if len(p) < 7 or not p[6]: continue
        sid, src, sport, dst, dport, seq, pay = p[:7]
        data = bytes.fromhex(pay.replace(":", ""))
        drc = "S" if src == "66.135.19.7" else "C"
        key = (int(sid), drc)
        streams.setdefault(key, {"sport": int(dport) if drc == "S" else int(sport), "c": []})
        streams[key]["c"].append((int(seq), data))
    for k, s in streams.items():
        s["data"] = b"".join(d for _, d in sorted(s["c"]))
    return streams

def crc8_pingu(key, data):
    table = []
    for i in range(256):
        v = i
        for _ in range(8):
            v = ((v << 1) ^ 7) & 0xFF if (v & 0x80) else (v << 1) & 0xFF
        table.append(v)
    crc = key & 0xFF
    for b in data:
        crc = table[crc ^ b]
    return crc

def ssc_dec(key, data):
    """SimpleStream.kt simpleStreamDecrypt3 — 与项目实现一致"""
    buf = bytearray(data)
    size = len(buf); bc = size >> 2; rem = size & 3
    mk = (key ^ 0x53351D9C) & 0xFFFFFFFF
    if bc > 0:
        past = mk ^ struct.unpack_from("<I", buf, 0)[0]
        struct.pack_into("<I", buf, 0, past)
        for i in range(bc - 1):
            mk = (mk - 0x63CAACE3) & 0xFFFFFFFF
            idx = (i + 1) * 4
            cur = struct.unpack_from("<I", buf, idx)[0]
            cp = mk ^ cur ^ past
            struct.pack_into("<I", buf, idx, cp)
            past = cp
        mk = past
    if rem:
        st = bc * 4
        for i in range(rem):
            buf[st + i] ^= (mk >> (i * 8)) & 0xFF
    return bytes(buf)

def dec_s2c(data):
    pkts = []
    for start_seq in (40, 43):
        seq = start_seq; pos = 0; out = []
        n = len(data)
        while pos < n:
            hc = (231 ^ (102 + seq)) & 0xFF
            if data[pos] == hc and pos + 6 <= n:
                ln = struct.unpack_from(">I", data, pos + 2)[0] ^ 0x96CA5395
                if 0 < ln < 200000 and pos + 6 + ln + 1 <= n:
                    payload = ssc_dec(seq, data[pos+6:pos+6+ln])
                    ok = crc8_pingu(seq & 0xFF, payload) == data[pos+6+ln]
                    out.append({"seq": seq, "ln": ln, "p": payload, "crc_ok": ok})
                    pos += 6 + ln + 1
                    seq = (seq + 3) & 0xFFFFFFFF
                    continue
            pos += 1
        if len(out) > len(pkts): pkts = out
    return pkts

def dec_c2s(data, start_seq=40):
    pkts = []
    seq = start_seq; pos = 0; n = len(data)
    while pos < n:
        hc = (231 ^ (192 + seq)) & 0xFF
        if data[pos] != hc:
            pos += 1; continue
        if pos + 5 > n: break
        uid = struct.unpack_from("<H", data, pos + 1)[0] ^ 0xA569
        ln = data[pos + 3] ^ 0x69
        if pos + 4 + ln + 1 > n:
            pos += 1; continue
        payload = ssc_dec(seq, data[pos+4:pos+4+ln])
        ok = crc8_pingu(seq & 0xFF, payload) == data[pos+4+ln]
        pkts.append({"seq": seq, "ln": ln, "p": payload, "crc_ok": ok, "uid": uid})
        pos += 4 + ln + 1
        seq = (seq + 3) & 0xFFFFFFFF
    return pkts

def ssc_enc(key, data):
    """simpleStreamEncrypt3 —— ssc_dec 的精确逆变换 (重封裝 replay 用, 如 replay_connect_cs.bin)"""
    buf = bytearray(data)
    size = len(buf); bc = size >> 2; rem = size & 3
    mk = (key ^ 0x53351D9C) & 0xFFFFFFFF
    if bc > 0:
        p0 = struct.unpack_from("<I", buf, 0)[0]
        struct.pack_into("<I", buf, 0, mk ^ p0)
        past = p0
        for i in range(bc - 1):
            mk = (mk - 0x63CAACE3) & 0xFFFFFFFF
            idx = (i + 1) * 4
            cur = struct.unpack_from("<I", buf, idx)[0]
            struct.pack_into("<I", buf, idx, mk ^ cur ^ past)
            past = cur
        mk = past
    if rem:
        st = bc * 4
        for i in range(rem):
            buf[st + i] ^= (mk >> (i * 8)) & 0xFF
    return bytes(buf)

def brute_s2c(raw, max_seq=2000):
    """半截流单帧暴力对齐 seq (跨 pcap 的连接尾巴/抓包中断处, dump() 解不出时用)。
    返回 [(seq, payload)], 仅列 CRC8 通过者。
    注意: ssc 进完整 seq, CRC8 key 取 seq&0xFF —— seq>255 时两者不同, 传错解不出。"""
    hits = []
    if len(raw) < 7:
        return hits
    for seq in range(40, max_seq):
        if (231 ^ (102 + seq)) & 0xFF != raw[0]:
            continue
        ln = struct.unpack_from(">I", raw, 2)[0] ^ 0x96CA5395
        if ln <= 0 or 6 + ln + 1 != len(raw):
            continue
        pay = ssc_dec(seq, raw[6:6+ln])
        if crc8_pingu(seq & 0xFF, pay) == raw[6+ln]:
            hits.append((seq, pay))
    return hits

def brute_c2s(raw, max_seq=2000):
    """同 brute_s2c, C->S 方向"""
    hits = []
    if len(raw) < 6:
        return hits
    for seq in range(40, max_seq):
        if (231 ^ (192 + seq)) & 0xFF != raw[0]:
            continue
        ln = raw[3] ^ 0x69
        if ln <= 0 or 4 + ln + 1 != len(raw):
            continue
        pay = ssc_dec(seq, raw[4:4+ln])
        if crc8_pingu(seq & 0xFF, pay) == raw[4+ln]:
            hits.append((seq, pay))
    return hits

def hx(b, w=48):
    h = b[:w].hex(' ')
    asc = ''.join(chr(x) if 32 <= x < 127 else '.' for x in b[:w])
    return f"{h} |{asc}|{'...' if len(b) > w else ''}"

def dump(pcap):
    streams = get_streams_full(pcap)
    print("=" * 118)
    print(f"PCAP: {os.path.basename(pcap)}")
    for (sid, drc), s in sorted(streams.items()):
        data = s["data"]
        print(f"\n--- stream {sid} {'S->C' if drc == 'S' else 'C->S'} (服务器端口 {s['sport']}) {len(data)}B")
        if drc == "S":
            if data[:1] == b"\x00" and len(data) > 3:
                bl = struct.unpack_from(">H", data, 1)[0]
                if 3 + bl <= len(data):
                    print(f"  BANNER(seq=40) len={bl} {hx(data[3:3+bl])}")
            pkts = dec_s2c(data)
            ok = sum(1 for p in pkts if p["crc_ok"])
            print(f"  {len(pkts)} 加密包, CRC8 通过 {ok}/{len(pkts)}")
            for p in pkts:
                q = p["p"]
                op = struct.unpack(">H", q[:2])[0] ^ 0xA569 if len(q) >= 2 else -1
                mark = "" if p["crc_ok"] else "  <<CRC失败"
                print(f"  sq={p['seq']:3d} ln={p['ln']:6d} op={op:3d} {SEND_BY.get(op, '??'):22s}{mark} {hx(q[2:])}")
        else:
            pkts = dec_c2s(data)
            ok = sum(1 for p in pkts if p["crc_ok"])
            uids = sorted(set(p["uid"] for p in pkts))
            print(f"  {len(pkts)} 包, CRC8 通过 {ok}/{len(pkts)}, uid={uids}")
            for p in pkts:
                q = p["p"]
                op = struct.unpack(">H", q[:2])[0] ^ 0xA569 if len(q) >= 2 else -1
                mark = "" if p["crc_ok"] else "  <<CRC失败"
                print(f"  sq={p['seq']:3d} uid={p['uid']:4d} ln={p['ln']:3d} op={op:3d} {RECV_BY.get(op, '??'):22s}{mark} {hx(q[2:])}")

if __name__ == "__main__":
    for pcap in (sys.argv[1:] or ["cap/start.pcap", "cap/login.pcap", "cap/enter-channel.pcap"]):
        dump(pcap)
