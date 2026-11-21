package bnbemu.packet.shop

import bnbemu.data.shop.ShopCatalogData
import bnbemu.data.shop.ShopItemEntry
import bnbemu.netty.PKT

/**
 * 进频道握手推送 #10: 商城目录与角色库存 (Opcode 65, S→C)
 *
 * 主数据加载与查询已收口至 [ShopCatalogData]。
 */
fun ShopCatalog(): PKT = ShopCatalog(null)

fun ShopCatalog(items: List<ShopItemEntry>?) = PKT {
    val blob = if (items == null) ShopCatalogData.CACHED_BLOB else ShopCatalogData.encode(items)
    Encode4(blob.size)
    EncodeBuffer(blob)
}
