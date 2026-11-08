package bnbemu.packet

import bnbemu.netty.PKT

/**
 * 个人交易市场/寄售物品条目
 */
data class MarketItemEntry(
    val itemId: UInt = 0u,
    val price: UInt = 0u,
    val name: String = "",
    val duration: UInt = 0u,
    val status: UShort = 0u,
) {
    companion object {
        val EMPTY = MarketItemEntry()
    }
}

/**
 * S→C 个人交易市场物品列表 (Opcode 310)
 *
 * 逆向确认 (CA.exe, 2026-11-08):
 * 1. 位于 CUserMarketStage::OnPacket (0x00631C78)，收到 0x136 (310) 时调用 0x0062EA8C -> 0x0062E1B7。
 * 2. 字段结构:
 *    Encode2(count)
 *    每个条目:
 *      Encode4(itemId)
 *      Encode4(price / count)
 *      EncodeStr(itemName)
 *      Encode4(duration / expire)
 *      Encode2(statusFlags)
 *      [若开启 0x20 特性额外 Encode1(extra)]
 * 3. 当 count=0 时，客户端触发 UMItemNotFound (0x008B4184, "UserMarket Item Not Found") 提示。
 * 4. 抓包中 (enter-channel.pcap seq=64 与 94) 携带 1 条全 0 空条目 (20B)，表示当前市场列表为空/占位。
 */
fun UserMarketItemList(items: List<MarketItemEntry> = listOf(MarketItemEntry.EMPTY)) = PKT {
    Encode2(items.size)
    for (item in items) {
        Encode4(item.itemId.toInt())
        Encode4(item.price.toInt())
        EncodeStr(item.name)
        Encode4(item.duration.toInt())
        Encode2(item.status.toInt())
    }
}
