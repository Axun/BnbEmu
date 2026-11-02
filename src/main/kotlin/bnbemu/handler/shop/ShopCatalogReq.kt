package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.ShopCatalog

// C->S op 276: 商城目录请求 (ShopVersionReq / ShopCatalogReq)
// 当客户端本地 fxstock.ini 版本不匹配或缺失时，于 0x00670D2A 发出此包请求全量商城目录。
// 服务端回应 S->C op 65 ShopCatalog。
val ShopCatalogReq = PKTHandler { c ->
    c send ShopCatalog()
}
