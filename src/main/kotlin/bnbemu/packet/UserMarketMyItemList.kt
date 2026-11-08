package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S→C 个人交易市场我的寄售物品列表 (Opcode 302)
 *
 * 逆向确认 (CA.exe, 2026-11-08):
 * 1. 位于 CUserMarketStage (0x0062E02F / 0x0062F4A0)，与 Op 310 同构:
 *    Encode2(count)
 *    每个条目:
 *      Encode4(itemId)
 *      Encode4(price / count)
 *      EncodeStr(itemName)
 *      Encode4(duration / expire)
 *      Encode2(statusFlags)
 *      [若开启 0x20 特性额外 Encode1(extra)]
 * 2. 在进频道批次回应 (enter-channel.pcap seq=67 与 97) 中携带 1 条全 0 空条目 (20B)。
 * 3. 在练习房抓包 (practice_s2c_op302_24B_0.bin) 中携带 1 条物品 (24B):
 *    itemId=19888, price=2004, name="Camo", duration=240000, status=0。
 */
fun UserMarketMyItemList(items: List<MarketItemEntry> = listOf(MarketItemEntry.EMPTY)) = PKT {
    Encode2(items.size)
    for (item in items) {
        Encode4(item.itemId.toInt())
        Encode4(item.price.toInt())
        EncodeStr(item.name)
        Encode4(item.duration.toInt())
        Encode2(item.status.toInt())
    }
}
