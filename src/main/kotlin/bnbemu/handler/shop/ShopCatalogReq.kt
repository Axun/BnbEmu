package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.shop.ShopCatalog

// C->S op 276: 商城目录请求 (ShopVersionReq / ShopCatalogReq)
// 当客户端本地 fxstock.ini 版本不匹配或缺失时，于 0x00670D2A 发出此包请求全量商城目录。
// 服务端回应 S->C op 65 ShopCatalog。
// 正常情况（op 261 的戳 = ShopCatalog.CATALOG_HASH）只在首次登录/目录变更后出现一次；
val ShopCatalogReq = PKTHandler { c ->
    println("[ShopCatalogReq] 客户端商城缓存未命中，重发 op 65 (${ShopCatalog.CACHED_BLOB.size} B)")
    c send ShopCatalog()
}
