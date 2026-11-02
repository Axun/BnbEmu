package bnbemu.handler.shop

import bnbemu.netty.PKTHandler

import bnbemu.packet.Unk66

// ============================================================================
// C→S op 258 (0x102): 商城 packagemap.ini 快取版本同步/請求
// ============================================================================
// 逆向確認:
//   CA.exe 0x00670D66 (CShopItemMan::CheckPackageMapRevision).
//   在進入商城階段 (0x004FB16E, EnterShopStage) 或進入商城界面時觸發:
//     比對本地 packagemap.ini 快取版本號與伺服器下發的 rev3;
//     若本地檔案不存在或版本不一致, 客戶端於 0x00670DD2 自動向伺服器發送 C→S op 258 (空載荷).
//   伺服器回應 S→C op 66 Unk66 (ShopPackageMap).
val ShopPackageMapReq = PKTHandler { c ->
    c send Unk66()
}
