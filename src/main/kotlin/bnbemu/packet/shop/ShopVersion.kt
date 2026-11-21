package bnbemu.packet.shop

import bnbemu.data.ShopCatalogData
import bnbemu.netty.PKT

// ============================================================================
// S→C op 261: 商城目錄版本校驗與開關通知 (進頻道握手推送 #15, seq=142, 5B)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00503402 (CStage::OnPacket).
//   欄位結構:
//     Encode1: enableShop (存入 CMainSystem::Instance() + 0x9C, 1 為啟用商城)
//     Encode4: catalogVersionHash
//   客戶端處理邏輯:
//     傳入 CShopItemMan::Instance().CheckRevision(catalogVersionHash) (0x00670CBE, 0x00994D08).
//     比對本地 fxstock.ini 的版本戳 —— 注意該值是**壓縮塊** `[0x01][rawSize][zlib]` 的 Adler-32,
//     不是抓包裡未壓縮塊的 0x75D92163。見 ShopCatalog.CACHE_BLOCK / CATALOG_HASH。
//       若版本不一致或未載入, 於 0x00670D2A 自動向伺服器發送 C→S op 276 (0x114) 請求商城完整資料.
fun ShopVersion(
    enableShop: Boolean = true,
    catalogVersion: Int = ShopCatalogData.CATALOG_HASH
) = PKT {
    Encode1(if (enableShop) 1 else 0)
    Encode4(catalogVersion)
}
