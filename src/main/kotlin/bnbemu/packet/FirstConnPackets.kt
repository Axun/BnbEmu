package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA

// EN 首連推送序列的三個附屬包, 內容取自 start.pcap stream 0 (banner 之後伺服器主動推送),
// 生成工具: tools/extract_first_push.py; 名稱為推測, opcode 對應見 EN_18_send.yaml

// opcode 193 (sq=43): payload 僅 Encode1(0)
fun FirstSessionNotify() = PKT {
    Encode1(0)
}

// opcode 67 (sq=49): payload 僅 Encode1(0), 緊跟在 FirstInfoInit 之後
fun FirstInfoTail() = PKT {
    Encode1(0)
}

// opcode 51 (sq=55): 頻道名資料塊
// 解碼 (每 dword BE 讀取 ^ 0x96CA5395 後以 LE 寫回) = filterstr.ssd 快取體:
//   181b0000 | 00 00 01 00 00 | 09 00 00 00 "Channel 1" | 06 00 00 00 "Normal"
fun MultiServerTail() = PKT {
    Encode1Bool(true)   // 5b
    Encode4(32)         // 資料塊長度
    EncodeBuffer(EN_CHANNEL_NAMES_BLOB)
}

private val EN_CHANNEL_NAMES_BLOB = """
    96ca488d 96cb5395 96ca5a95 f7a21095
    faaf3dfb 96cc62b5 f9845395 faab3ee7
""".toBA()
