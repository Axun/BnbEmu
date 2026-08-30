package pingu.packet

import pingu.netty.PKT
import pingu.netty.toBA

// EN 頻道進入推送序列第 3 包 (login.pcap sq=52, 緊跟 ChannelsState 之後)
// SSD 資料塊 (21B): 解碼 = 18100000 | 00 00 | 全 0 ... | Encode1(0) 原樣尾, 語義未知
fun Unk12() = PKT {
    Encode4(21)
    EncodeBuffer(EN_UNK12_BLOB)
}

private val EN_UNK12_BLOB = """
    96ca438d 96ca5395 96ca5395 96ca5395 96ca5395 5a
""".toBA()
