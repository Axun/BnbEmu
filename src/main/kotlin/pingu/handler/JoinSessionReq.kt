package pingu.handler

import pingu.netty.PKTHandler

// C→S op 81 進頻道自動空包 —— ⚠️ 不是「進房」請求
// =================================================
// 位置: enter-channel.pcap stream1 sq=61, 在批次 297/302/310/64/293/78 之後、274/281/276 之前;
//       用戶進頻道後無任何操作也會發出, 屬進頻道握手流程的一環 (與 274/281/276 同批, 見 AGENTS.md 步驟7)
// 載荷: 空 (ln=2 僅 opcode)
// 行為: 真實伺服器不立即回應 —— sq=61 之後的 seq=64~97 是 78 批次鏈回應, 與 81 無關
// 綁定: 空 handler, 只消費不回包, 消除 "找不到 Handler" 噪音 (對齊真實行為)

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
//   尾 2B LE 讀 ^0xA569 按 BE 寫, 尾 1B ^0x5A).
//
// 樣本:
//   101B (enter-channel, 8 人, 槽位 0..7):
//     0 Static   6911 | 1 Mirage 6600 | 2 Lotso 3966 | 3 Kuso 3968
//     4 Jacko    3205 | 5 Hello  119  | 6 ffdf  7215 | 7 suan 5352
//   131B (login, 10 人): Cadeau/MDuri/suan/Motel/dduu/PolarBear/micro/Caeter/wesson/oubao
//
// 尚未實作 (S→C 81 為下一缺口); 實作時令牌/帳號數值可複用 SessionList 的 encodeName 與
// 抓包常數 (CAPTURE_USER_ID/SESSION_STRING, 見 AskFirstInfoResult.kt)
// server = C???::On??? (原始名稱未知, 綁定見 EN_0_recv.yaml JoinSessionReq)
val JoinSessionReq = PKTHandler { _ ->
}
