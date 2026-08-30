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


def replay(name: str, blob: bytes, expect_ops: set, expect_payloads: dict = None):
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
        for op, hx in (expect_payloads or {}).items():
            got = [p["p"] for p in good
                   if len(p["p"]) >= 2 and struct.unpack(">H", p["p"][:2])[0] ^ 0xA569 == op]
            assert got, f"[{name}] 沒收到 op {op} 回應"
            assert got[0].hex() == hx, \
                f"[{name}] op {op} 載荷與抓包不一致:\n  got {got[0].hex(' ')}\n  exp {hx}"
            print(f"[{name}] op {op} 載荷逐位元組與抓包一致 ✓ ({len(got[0])}B)")
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
    # enter-channel.pcap stream1: op7 OnConnEstablished → EN 應回 [48,13,14,97,122,87,423]
    #   (87 RoomList 房間列表); 其後批次 297/302/310/64/293/78 → Unk_78 觸發整套回應
    #   [310,302,254,72 用戶列表,293,78,4×2,227,60,310,302]; 尾部 15 輪詢 → 13
    # op 72 SessionList / op 87 RoomList 已結構化, 載荷須與抓包逐位元組一致
    replay("enter", open(os.path.join(base, "tools/replay_enter_cs.bin"), "rb").read(),
           {48, 13, 14, 97, 122, 87, 423, 310, 302, 254, 72, 293, 78, 4, 227, 60},
           {72: open(os.path.join(base, "cap/chan_s2c_op72_396B_0.bin"), "rb").read().hex(),
            87: open(os.path.join(base, "cap/chan_s2c_op87_41B_0.bin"), "rb").read().hex()})
    # op 6 流程: 真實請求載荷 (E1(10)+E4(2), seq70/uid198 原幀以 ssc_enc 重封裝至 seq=40)
    # → EN 必須回 **op 7** ResConnectToSvr (客戶端等 7 才會開進頻道連線; 回 6 客戶端會忽略)
    replay("connect", open(os.path.join(base, "tools/replay_connect_cs.bin"), "rb").read(), {7})
    print("ALL PASS")
