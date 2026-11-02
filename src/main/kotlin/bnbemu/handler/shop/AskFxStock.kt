package bnbemu.handler.shop

import bnbemu.netty.PKTHandler
import bnbemu.packet.shop.FxStockInit

// server = CMainPool::OnRequestShopItemList | CItemMan::UpdateShopItemPacket
val AskFxStock = PKTHandler { c ->
    c send FxStockInit()
}