package bnbemu.packet.shop

import bnbemu.isEN
import bnbemu.netty.PKT
import bnbemu.netty.toBA

// EN: 進頻道握手推送 #3 (C→S 81 空包後, seq=106, 103B) — 原樣重放
// TW: 原作者舊實作 (EN 下不觸發)
fun FxStockInit() = PKT {
    if (isEN) {
        EncodeBuffer(EN_FX_STOCK_INIT_BLOB)
    } else {
        val test = ("18 03 00 00 00 00 00 00").toBA()
        Encode4(test.size)
        EncodeBuffer(test)
    }
}

private val EN_FX_STOCK_INIT_BLOB = """
    5896ca53 95a56996 ca5395a5 6996ca53 95a56996 ca5395a5
    6996ca53 95a56996 ca5395a5 6996ca53 95a56996 ca5394a5
    6ac42d35 96ca5395 a56996ca 5395a569 96ca5395 a56996ca
    5395a569 96ca5395 a56996ca 5395a569 96ca5395 a56996ca
    5394a56a c42d35
""".toBA()
