package bnbemu.packet

import bnbemu.netty.PKT

// EN 首連推送序列的三個附屬包, 內容取自 start.pcap stream 0 (banner 之後伺服器主動推送),
// 生成工具: tools/extract_first_push.py; 名稱為推測, opcode 對應見 opcode_send.yaml

// opcode 193 (sq=43): payload 僅 Encode1(0)
fun FirstSessionNotify() = PKT {
    Encode1(0)
}

// opcode 67 (sq=49): payload 僅 Encode1(0), 緊跟在 FirstInfoInit 之後
fun FirstInfoTail() = PKT {
    Encode1(0)
}
