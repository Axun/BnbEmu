package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.UserItemList

// C→S 264 背包資料查詢 (CLobbyStage 0x00513226 構造空包)
val UserItemListReq = PKTHandler { c ->
    c send UserItemList()
}
