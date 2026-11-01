package pingu.handler

import pingu.netty.PKTHandler
import pingu.packet.ChannelsGameState
import pingu.packet.EnableShop
import pingu.packet.shop.FxStockInit
import pingu.packet.JoinSessionResult
import pingu.packet.RoomList
import pingu.packet.SessionList
import pingu.packet.Unk122
import pingu.packet.Unk204
import pingu.packet.Unk20
import pingu.packet.Unk247
import pingu.packet.Unk261
import pingu.packet.Unk274
import pingu.packet.Unk413
import pingu.packet.Unk423
import pingu.packet.ShopCatalog
import pingu.packet.UserItemList
import pingu.packet.Unk66
import pingu.packet.UserInventory
import pingu.packet.Unk212

// C→S op 81 進頻道自動空包 —— ⚠️ 不是「進房」請求
// =================================================
// 位置: enter-channel.pcap stream1 sq=61, 在批次 297/302/310/64/293/78 之後、274/281/276 之前;
//       用戶進頻道後無任何操作也會發出, 屬進頻道握手流程的一環 (與 274/281/276 同批, 見 AGENTS.md 步驟7)
// 載荷: 空 (ln=2 僅 opcode)
// 行為: 真實伺服器不回 81 的逐包回應, 而是跟發整條「進頻道握手推送」(2026-08-31 時序對齊確認:
//       客戶端發 81 後會等這條推送才繼續發 274/281/276, 缺它客戶端卡「正在連接」對話框):
//         seq=100~121: [413, 198, 200 FxStockInit, 274×2, 122, 212, 210 EnableShop]
//         seq=124~127: [87 RoomList, 65(50KB)]          ← 客戶端 274/274/281 之後
//         seq=130~148: [204, 66, 14(9B 變體), 20, 261, 247, 423]
//         seq=151~154: [81 名冊, 72 用戶列表]            ← 之後大廳期週期重發 (週期未實作)
// S→C op 81 JoinSessionResult 名冊 (大廳期週期推送, 非 81 請求的回應)
// ==================================================================
// 證據: login.pcap 登入連線從無 C→S 81 請求, 但 S→C op 81 名冊仍有 4 次週期推送
//       (sq=112/121/184/199); 結構已由 4 個 131B + 4 個 101B 樣本全量解出:
//
//   E4(人數 N) + N × [ E1(槽位序號 0..N-1)  E4(帳號數值)  E2(名長)  角色名令牌 ]
//
//   與 op 72 SessionList 同玩家集合但**結構不同**: op 81 無等級/頻道容量欄,
//   多帳號數值與槽位序號, 按槽位排序而非等級; 名冊長度隨房間人數變化.
//   帳號數值與 passport 0x2E 回應的 u32(帳號數值) 一致 (ffdf = 7215).
//   令牌編碼 = SessionList.kt encodeName 同款 (4B 塊 LE 讀 ^0x96CA5395 按 BE 寫,
//   尾 2B LE 讀 ^0xA569 按 BE 寫, 尾 1B ^0x5A). 結構化實作見 packet/JoinSessionResult.kt
// server = C???::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml JoinSessionReq)
val JoinSessionReq = PKTHandler { c ->
    c.send(
        Unk413(), UserInventory(), FxStockInit(),
        Unk274(), Unk274(1),
        Unk122(), Unk212(), EnableShop(),
        RoomList(), ShopCatalog(),
        Unk204(), Unk66(), ChannelsGameState(tailOnes = 2),
        Unk20(), Unk261(), Unk247(), Unk423(),
        JoinSessionResult(), SessionList(),
    )
}
