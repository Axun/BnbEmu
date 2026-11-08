# -*- coding: utf-8 -*-
"""
幀格式回歸測試: 把 cap/*.pcap 裡真實客戶端的 C→S 原始位元組流原樣回放給本地模擬器,
驗證 Codec.kt 新幀格式 (banner / degree-3 S→C / [hc][uid][len^0x69] C→S) 收發兩端正確。

用法: 先啟動模擬器, 再 python tools/replay_test.py
"""
import socket, sys, time, struct, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import dec_s2c

BANNER_BODY = bytes.fromhex("0000120012000000000100000000000037000000")
PORT = 3838
# 與 src/main/kotlin/bnbemu/packet/AskFirstInfoResult.kt 的 CHANNEL_TAB_COUNT 同步:
# 頻道版 65B op4 抓包末位元組為 E1(0), 模擬器兩版統一 E1(3) 以保留頻道頁籤保存功能
CHANNEL_TAB_COUNT = 3

# 與 src/main/kotlin/bnbemu/packet/ChannelsExtraInfo.kt 的 CHANNEL_GRADE_NORMAL 同步:
# op11 抓包 5 條頻道的「頻道類型」位元組為 [0, 50, 0, 1, 1]。50 (Ranked) 是排位頻道
# 必須保留; 分級 1 會讓客戶端 sub_629FDB 的推薦頻道掃描 (a2=1, 玩家等級 1) 命中,
# 強制跳回 Beginner 頁並把 0 回寫 LastChannelType, 故只把 Town / SquareCompat 改成 0,
# 這 2B 刻意與抓包不同; 基準檔本身不動, 只換算期望值, 其餘 216B 仍逐位元組斷言。
# 注意 50..65 / 70 是排位頻道 (sub_4DC672), 不能拿來當中性值 — 全部設 50 會讓
# 所有大廳都禁用練習模式。ssdEncode 會把明文位元組重排, 下列絕對偏移由明文塊
# 重構 + ssdEncode 逐位元組比對得出 (blob 在包內 offset 7)。
OP11_GRADE_PATCHES = ((153, 0x52, 0x53), (198, 0x94, 0x95))


def op11_no_beginner_grade(base: bytes) -> bytes:
    out = bytearray(base)
    for off, old, new in OP11_GRADE_PATCHES:
        assert out[off] == old, f"op11 基準偏移 {off} 應為抓包分級位元組 0x{old:02X}, 實得 0x{out[off]:02X}"
        out[off] = new
    return bytes(out)


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


def op112_random_mask(p: bytes) -> set:
    """op112 每局會變動的欄位: Encode4(randomSeed) 4B + 每筆 17B 紀錄的第 2 欄 spawnPos。

    CA.exe 0x0051F29C 用 spawnPos 呼叫 SetSlotSpawnPos; 開局棋盤初始化再逐槽讀出。
    這兩個欄位由伺服器每局重新產生, 其餘欄位仍須與抓包逐位元組一致。
    """
    slot_count = p[26] ^ 0x5A  # Encode1(slotCount)
    return set(range(2, 6)) | {28 + 17 * n for n in range(slot_count)} | {7, 18, 23}


def welcome_notice_payload():
    """op 423 期望載荷: 依 packet/WelcomeNotice.kt 當前的預設 text/displayMs 生成 (含 2B opcode)。

    文案屬可自定義的廣告欄位, 不再與抓包基準逐位元組比對; 改文案時測試自動跟隨,
    仍逐位元組校驗 EncodeCipherStr + Encode4 的編碼。cap/practice_s2c_op423_26B_0.bin
    是原廠文案 "Welcome to PopTag!" 的基準, 僅供對照。
    """
    kt_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "src/main/kotlin/bnbemu/packet/WelcomeNotice.kt")
    kt = open(kt_path, encoding="utf-8").read()
    m = re.search(r'text:\s*String\s*=\s*"([^"]*)"', kt)
    d = re.search(r'displayMs:\s*Int\s*=\s*(\d+)', kt)
    assert m and d, "找不到 WelcomeNotice 的 text / displayMs 預設值"
    s = m.group(1).encode("ascii")
    out = bytearray(struct.pack(">H", 423 ^ 0xA569))
    out += struct.pack(">H", len(s) ^ 0xA569)
    i = 0
    while i + 4 <= len(s):
        out += struct.pack(">I", struct.unpack_from("<I", s, i)[0] ^ 0x96CA5395)
        i += 4
    if i + 2 <= len(s):
        out += struct.pack(">H", struct.unpack_from("<H", s, i)[0] ^ 0xA569)
        i += 2
    if i < len(s):
        out.append(s[i] ^ 0x5A)
    out += struct.pack(">I", int(d.group(1)) ^ 0x96CA5395)
    return bytes(out).hex()


def replay(name: str, blob: bytes, expect_ops: set, expect_payloads: dict = None,
           expect_all: dict = None, expect_payload_masks: dict = None):
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
            mask = (expect_payload_masks or {}).get(op)
            if mask is None:
                assert got[0].hex() == hx, \
                    f"[{name}] op {op} 載荷與抓包不一致:\n  got {got[0].hex(' ')}\n  exp {hx}"
                print(f"[{name}] op {op} 載荷逐位元組與抓包一致 ✓ ({len(got[0])}B)")
            else:
                masked = mask(got[0]) if callable(mask) else mask
                exp = bytes.fromhex(hx)
                assert len(got[0]) == len(exp), f"[{name}] op {op} 長度不符: {len(got[0])} vs {len(exp)}"
                diff = [i for i, (a, b) in enumerate(zip(got[0], exp)) if a != b]
                bad = [i for i in diff if i not in masked]
                assert not bad, \
                    f"[{name}] op {op} 非隨機欄位與抓包不一致 (offsets={bad}):\n  got {got[0].hex(' ')}\n  exp {hx}"
                if op == 112:
                    slot_count = got[0][26] ^ 0x5A
                    spawns = [got[0][28 + 17 * n] ^ 0x5A for n in range(slot_count)]
                    assert all(0 <= s <= 7 for s in spawns) and len(set(spawns)) == slot_count, \
                        f"[{name}] op112 spawnPos 必須是 0..7 的不重複排列: {spawns}"
                    print(f"[{name}] op112 出生點排列 = {spawns}")
                print(f"[{name}] op {op} 固定欄位逐位元組與抓包一致 ✓ "
                      f"(忽略 {len(masked)} 個隨機欄位位元組, 實際變動 {len(diff)}B)")
        for op, hxs in (expect_all or {}).items():
            got = [p["p"].hex() for p in good
                   if len(p["p"]) >= 2 and struct.unpack(">H", p["p"][:2])[0] ^ 0xA569 == op]
            assert got == list(hxs), \
                f"[{name}] op {op} 出現次數/順序/內容不符:\n  got {[len(g) for g in got]} 個\n  exp {[len(h) for h in hxs]} 個"
            print(f"[{name}] op {op} {len(got)} 次出現全部逐位元組與抓包一致 ✓")
    else:
        assert not expect_ops, f"[{name}] 沒收到任何 S→C 加密包 (缺少 {expect_ops})"
        print(f"[{name}] C→S ✓ 整段 {len(blob)}B 全部接受, 連線保持 (該流程模擬器尚無對應回應)")
    s.close()


if __name__ == "__main__":
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # start.pcap stream0: op0 首連開啟 → 應觸發啟動推送 193 | 2 | 67 | 1 | 51
    replay("first", open(os.path.join(base, "tools/replay_first_cs.bin"), "rb").read(), {193, 2, 67, 1, 51})
    # login.pcap stream2: op2 AskFirstInfo → 應回 op4; op55 → [11,13,12]; op11 → [11,13]
    # op 11 ChannelsExtraInfo / op 13 ChannelsState / op 4 AskFirstInfoResult 已結構化,
    # 載荷須與抓包逐位元組一致
    # (op11 基準為 IP 改寫版: 抓包原始 66.135.19.7 → 127.0.0.1, 與模擬器下發一致)
    # op11 的 2 個頻道類型位元組刻意偏離抓包 (見 op11_no_beginner_grade), 基準檔不改只換算期望值
    replay("login", open(os.path.join(base, "tools/replay_login_cs.bin"), "rb").read(), {4, 11, 13, 12},
           {4: open(os.path.join(base, "cap/login_s2c_op4.bin"), "rb").read().hex(),
            11: op11_no_beginner_grade(open(os.path.join(base, "cap/login_s2c_op11_ip127.bin"), "rb").read()).hex(),
            13: open(os.path.join(base, "cap/login_s2c_op13.bin"), "rb").read().hex()})
    # enter-channel.pcap stream1: op7 OnConnEstablished → EN 應回 [48,13,14,97,122,87,423]
    #   (87 RoomList 房間列表); 其後批次 297/302/310/64/293/78 → StageInitReq (op 78) 觸發整套回應
    #   [310,302,254,72 用戶列表,293,78,4×2,227,60,310,302]; C 的 81 空包 → 進頻道握手推送
    #   [413,198,200,274×2,122,212,210,87,65,204,66,14(9B),20,261,247,423,81 名冊,72]
    # op 72 SessionList / op 87 RoomList / op 81 JoinSessionResult 已結構化, 載荷須與抓包逐位元組一致
    # ⚠️ 頻道版 65B op4 只有末位元組 E1(channelTabCount) 與抓包不同 (抓包 0, 模擬器 3),
    #    基準檔本身不改動, 只把期望值的末位元組換算成 Encode1(CHANNEL_TAB_COUNT), 其餘 64B 仍逐位元組比對
    chan_op4_65b = bytearray(open(os.path.join(base, "cap/chan_s2c_op4_65B_0.bin"), "rb").read())
    assert chan_op4_65b[-1] == 0x5A, f"頻道版 op4 基準末位元組應為 Encode1(0): {chan_op4_65b[-1]:02X}"
    chan_op4_65b[-1] = 0x5A ^ CHANNEL_TAB_COUNT
    replay("enter", open(os.path.join(base, "tools/replay_enter_cs.bin"), "rb").read(),
           {48, 13, 14, 97, 122, 87, 423, 310, 302, 254, 72, 293, 78, 4, 227, 60,
            413, 198, 200, 274, 212, 210, 65, 204, 66, 20, 261, 247, 81},
           {72: open(os.path.join(base, "cap/chan_s2c_op72_396B_0.bin"), "rb").read().hex(),
            87: open(os.path.join(base, "cap/chan_s2c_op87_41B_0.bin"), "rb").read().hex(),
            254: open(os.path.join(base, "cap/chan_s2c_op254_56B_0.bin"), "rb").read().hex(),
            227: open(os.path.join(base, "cap/chan_s2c_op227_37B_0.bin"), "rb").read().hex(),
            413: open(os.path.join(base, "cap/chan_s2c_op413_616B_0.bin"), "rb").read().hex(),
            198: open(os.path.join(base, "cap/chan_s2c_op198_poshevie.bin"), "rb").read().hex(),
            200: open(os.path.join(base, "cap/chan_s2c_op200_105B_0.bin"), "rb").read().hex(),
            212: open(os.path.join(base, "cap/chan_s2c_op212_22B_0.bin"), "rb").read().hex(),
            65: open(os.path.join(base, "cap/chan_s2c_op65_poshevie.bin"), "rb").read().hex(),
            204: open(os.path.join(base, "cap/chan_s2c_op204_14B_0.bin"), "rb").read().hex(),
            66: open(os.path.join(base, "cap/chan_s2c_op66_14B_0.bin"), "rb").read().hex(),
            20: open(os.path.join(base, "cap/chan_s2c_op20_46B_0.bin"), "rb").read().hex(),
            261: open(os.path.join(base, "cap/chan_s2c_op261_7B_0.bin"), "rb").read().hex(),
            247: open(os.path.join(base, "cap/chan_s2c_op247_6B_0.bin"), "rb").read().hex(),
            81: open(os.path.join(base, "cap/chan_s2c_op81_101B_0.bin"), "rb").read().hex()},
           {4: [open(os.path.join(base, "cap/chan_s2c_op4_13B_0.bin"), "rb").read().hex(),
                chan_op4_65b.hex()],
            274: [open(os.path.join(base, "cap/chan_s2c_op274_17B_0.bin"), "rb").read().hex(),
                  open(os.path.join(base, "cap/chan_s2c_op274_17B_1.bin"), "rb").read().hex()],
            14: [open(os.path.join(base, "cap/chan_s2c_op14_10B_0.bin"), "rb").read().hex(),
                 open(os.path.join(base, "cap/chan_s2c_op14_9B_0.bin"), "rb").read().hex()]},
            {227: set(range(3, 7)), 261: set(range(3, 7))})
    # op 6 流程: 真實請求載荷 (E1(10)+E4(2), seq70/uid198 原幀以 ssc_enc 重封裝至 seq=40)
    # → EN 必須回 **op 7** ResConnectToSvr (客戶端等 7 才會開進頻道連線; 回 6 客戶端會忽略)
    replay("connect", open(os.path.join(base, "tools/replay_connect_cs.bin"), "rb").read(), {7})
    # 進練習房流程 (tst.pcap stream4): op 7 OnConnEstablished (seq=40) + op 75 練習房請求 (重封裝至 seq=43)
    #   + op 92 開始遊戲 (重封裝至 seq=46)。
    # → EN 回整套房間設置 (87, 83, 4×2, 113, 100, 108, 106×8, 每槽 114/104/105/103, 198, 423);
    #   op 92 觸發遊戲開始 [111, 112, 100(77B), 136]。
    #   各包 (83/113/100/108/136/198 為回放 blob, 423 由 WelcomeNotice 結構化生成並按當前預設文案比對)
#   載荷逐位元組一致; op 423 文案自定義後不再比對原廠抓包基準, 見 welcome_notice_payload();
    #   op 112 的 randomSeed 與每槽 spawnPos 每局重新產生, 只比對其餘固定欄位
    #   (詳見 op112_random_mask), 並額外驗證 spawnPos 是 0..7 的不重複排列。
    #   (op 139 只在收到 C→S op 106 後回顯, 見 tools/test_ai_sync.py)
    #   ⚠️ 真實伺服器另有 op 172 (MapEventSync 地圖事件時間軸同步, 驅動 "Wave/Game/AirPlane1"),
    #      模擬器不主動週期廣播, 故 expect_ops 不含 172 (逆向見 tools/rev_op172.py)
    replay("practice", open(os.path.join(base, "tools/replay_practice_cs.bin"), "rb").read(),
           {87, 83, 4, 113, 100, 108, 106, 114, 104, 105, 103, 198, 423, 111, 112, 136},
           {83: open(os.path.join(base, "cap/practice_s2c_op83_8B_0.bin"), "rb").read().hex(),
            113: open(os.path.join(base, "cap/practice_s2c_op113_4B_0.bin"), "rb").read().hex(),
            108: open(os.path.join(base, "cap/practice_s2c_op108_10B_0.bin"), "rb").read().hex(),
            112: open(os.path.join(base, "cap/practice_s2c_op112_167B_0.bin"), "rb").read().hex(),
            136: open(os.path.join(base, "cap/practice_s2c_op136_180B_0.bin"), "rb").read().hex(),
            198: open(os.path.join(base, "cap/practice_s2c_op198_poshevie.bin"), "rb").read().hex(),
            423: welcome_notice_payload()},
           expect_payload_masks={112: op112_random_mask})
    print("ALL PASS")
