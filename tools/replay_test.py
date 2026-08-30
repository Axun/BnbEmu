# -*- coding: utf-8 -*-
"""
幀格式回歸測試: 把 cap/*.pcap 裡真實客戶端的 C→S 原始位元組流原樣回放給本地模擬器,
驗證 Codec.kt 新幀格式 (banner / degree-3 S→C / [hc][uid][len^0x69] C→S) 收發兩端正確。

用法: 先啟動模擬器, 再 python tools/replay_test.py
"""
import socket, sys, time, struct, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c

BANNER_BODY = bytes.fromhex("0000120012000000000100000000000037000000")
PORT = 3838


def read_exact(s: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        d = s.recv(n - len(buf))
        if not d:
            raise ConnectionError(f"連線被關閉, 已收到 {buf.hex(' ')!r}")
        buf += d
    return buf


def collect(s: socket.socket, seconds: float = 1.5):
    s.settimeout(0.3)
    data, end, saw_eof = b"", time.time() + seconds, False
    while time.time() < end:
        try:
            d = s.recv(65536)
            if not d:
                saw_eof = True
                break
            data += d
        except socket.timeout:
            pass
    return data, saw_eof


def replay(name: str, blob: bytes, expect_ops: set):
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)

    # 1) banner: [0x00][len:2 BE 明文][payload 明文], 內容須與真實伺服器逐位元組一致
    hdr = read_exact(s, 3)
    assert hdr[0] == 0, f"[{name}] banner 首位元組非 0x00: {hdr[0]:02X}"
    blen = struct.unpack(">H", hdr[1:3])[0]
    body = read_exact(s, blen)
    assert body == BANNER_BODY, f"[{name}] banner 內容不一致: {body.hex(' ')}"
    print(f"[{name}] banner ✓ len={blen} 與抓包逐位元組一致")

    # 2) 一次性回放整段 C→S 位元組流 (多包黏在一起, 順便壓測解碼器分幀)
    s.sendall(blob)
    out, saw_eof = collect(s)

    # 3) 伺服器的 S→C 必須能用真實協議解碼 (seq 從 43 起), CRC8 全過
    pkts = dec_s2c(out)
    good = [p for p in pkts if p["crc_ok"]]
    ops = []
    for p in good:
        q = p["p"]
        ops.append(struct.unpack(">H", q[:2])[0] ^ 0xA569 if len(q) >= 2 else -1)
    assert not saw_eof, f"[{name}] 連線被伺服器斷開 (幀格式/CRC 解碼失敗)"
    if pkts:
        assert len(good) == len(pkts), f"[{name}] CRC8 失敗 {len(pkts)-len(good)}/{len(pkts)}"
        print(f"[{name}] S→C ✓ {len(pkts)} 包全部 CRC8 通過, ops={ops}")
        missing = expect_ops - set(ops)
        assert not missing, f"[{name}] 缺少預期回應 opcode: {missing}"
    else:
        assert not expect_ops, f"[{name}] 沒收到任何 S→C 加密包 (缺少 {expect_ops})"
        print(f"[{name}] C→S ✓ 整段 {len(blob)}B 全部接受, 連線保持 (該流程模擬器尚無對應回應)")
    s.close()


if __name__ == "__main__":
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # start.pcap stream0: op0 首連開啟 → 應觸發啟動推送 193 | 2 | 67 | 1 | 51
    replay("first", open(os.path.join(base, "tools/replay_first_cs.bin"), "rb").read(), {193, 2, 67, 1, 51})
    # login.pcap stream2: op2 AskFirstInfo → 應回 op4; op55 → [11,13,12]; op11 → [11,13]
    replay("login", open(os.path.join(base, "tools/replay_login_cs.bin"), "rb").read(), {4, 11, 13, 12})
    # enter-channel.pcap stream1: op7 OnConnEstablished (含 DecodeStr 字串解析) 等 13 包
    replay("enter", open(os.path.join(base, "tools/replay_enter_cs.bin"), "rb").read(), set())
    print("ALL PASS")
