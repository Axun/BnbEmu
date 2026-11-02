package pingu.handler.shop

import pingu.netty.PKTHandler

/**
 * C->S op 317: 商城连线就绪/状态同步确认 (ShopSyncReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 客户端收到 op 259 EnterShopResult 成功应答后，于 0x005BE70A 构造空包发送给服务端确认。
 */
val ShopSyncReq = PKTHandler { c ->
    // 空消费确认包
}
