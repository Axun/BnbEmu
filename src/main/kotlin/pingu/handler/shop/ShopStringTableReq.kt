package pingu.handler.shop

import pingu.netty.PKTHandler

import pingu.packet.Unk204

// ============================================================================
// C→S op 278 (0x116): 商城 stringtable.ini 快取版本同步/請求
// ============================================================================
// 逆向確認:
//   CA.exe 0x00670E05 (CShopItemMan::CheckStringTableRevision).
//   在進入商城階段 (0x004FB17A, EnterShopStage) 或進入商城界面時觸發:
//     比對本地 stringtable.ini 快取版本號與伺服器下發的 rev2;
//     若本地檔案不存在或版本不一致, 客戶端於 0x00670E71 自動向伺服器發送 C→S op 278 (空載荷).
//   伺服器回應 S→C op 204 Unk204 (ShopStringTable).
val ShopStringTableReq = PKTHandler { c ->
    c send Unk204()
}
