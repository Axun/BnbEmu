# -*- coding: utf-8 -*-
"""
進頻道連線回應提取: 從 enter-channel.pcap stream1 (60248) 按 TCP 線序解出 S→C 載荷,
存 cap/chan_s2c_op<op>_<len>B_<n>.bin (含 2B opcode 前綴), 供 Kotlin blob 常量生成
(Unk423 / Unk254 / Unk227 / ItemDef / SessionInfoResult; RoomList 與 SessionList 已結構化,
op 87 與 op 72 載荷分別作為兩者的 replay_test 逐字節斷言基準)。

注意: 線序 = 解碼位置順序 (seq 步進 3); dump_flow.py 按時間戳排序會因 TCP 合包失真。

用法: python tools/extract_channel_flow.py
"""
import os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decode_final import get_streams_full, dec_s2c

PCAP = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "cap/enter-channel.pcap")
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "cap")

if __name__ == "__main__":
    streams = get_streams_full(PCAP)
    s = streams[("1", "S")] if ("1", "S") in streams else streams[(1, "S")]
    pkts = dec_s2c(s["data"])
    seen = {}
    for p in pkts:
        if not p["crc_ok"] or len(p["p"]) < 2:
            continue
        op = struct.unpack(">H", p["p"][:2])[0] ^ 0xA569
        n = seen.get(op, 0)
        seen[op] = n + 1
        fn = os.path.join(OUT, f"chan_s2c_op{op}_{len(p['p'])}B_{min(n,1)}.bin")
        open(fn, "wb").write(p["p"])
    print(f"{len(pkts)} 包 (CRC8 全過), 線序:")
    print("  ".join(str(struct.unpack('>H', p['p'][:2])[0] ^ 0xA569) if len(p['p']) >= 2 else '?'
                     for p in pkts))
