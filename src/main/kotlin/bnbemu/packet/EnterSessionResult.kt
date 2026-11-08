package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 83 — 「進入 / 建立會話結果」(EN 客戶端處理鏈, 2026-11-08 反編譯釘死)
// ============================================================================
// 舊表名 SessionSimpleInfo 名不符實(已廢棄): 它不是「會話簡要資訊」的週期推送, 而是
// 「進房結果」—— 第一欄是結果碼, 失敗分支在客戶端全是彈窗, 成功分支才帶房間號。
//
// 處理鏈 (位址均可查 reference/CA.exe.c):
//   CLobbyStage::OnPacket 0x00510287 的 switch case 'S' (0x53 = 83) → 0x00507113
//   0x00507113:
//       (*stage->vtable[0x110])(0)                 清大廳等待鎖
//       result = Decode1                            → 跳表 0x00507C93 (0..12)
//       case 0  := 成功 → 特性 80 檢查 (sub_6C054E(80)) → 大廳策略虛表 +0x18 (0x00516C0F) → 0x00506513
//       case 1..12 := 失敗 → 只彈對應對話框, **不再讀任何欄位**(所以失敗包只有 1 位元組)
//   0x00516C0F (CNormalLobbyStrategy, 普通大廳):
//       flag = Decode1 → CFxContext[0x5A4]
//       然後 sub_68DC53(stageMgr, 12) 切進房間場景
//   0x00506513 (解析會話):
//       sessionId = Decode2 → 0x00505123 到 CLobbyStage 的房間快取陣列找同 id 的房
//                             (基底 +0x6968, 每筆 204B, 筆數 +0x6964, 由 op 87 房間列表填充)
//                             ⚠️ 找不到就整段跳過 —— id 必須跟 op 87 裡的房號一致才有意義
//       type      = Decode1 → 0x0068626F → CFxContext[0x570] = 房間類型
//       state     = Decode1 → 0x00687393 → CFxContext[0x1AC] = 會話狀態
//       找到房之後另外從快取補 name / 等級 / 人數, 並把房號寫進 CFxContext[0x190]
//
// 所以 body 固定 6 位元組:
//     E1(result) + E1(flag) + E2(sessionId) + E1(sessionType) + E1(state)
// 抓包 tst.pcap stream4 sq=190 = 5a 5a a5 22 55 5a → result=0, flag=0, id=75, type=15, state=0
//
// ⚠️ 第 2 個位元組(flag) 的讀取者是「大廳策略」而不是 0x00506513:
//     · 普通大廳 (0x00516C0F) / 任務大廳 (0x00515FE1) / 通關大廳 (0x005187CA)：Decode1 存進
//       CFxContext[0x5A4]，本體沒有任何讀取點(整個 .text 沒有 +0x5A4 的讀取指令)
//     · 兌換大廳 (0x005146AD)：當 bool 用，true → stage 17(商城)，false → stage 16
//     · 階梯/特殊賽大廳 (0x00515080 / 0x00517E0E)：**不讀**，此時後面的 id 會整體前移 1 位元組，
//       真實伺服器顯然是照「會讀 1 位元組」的版本發的，所以 flag 必須保留
fun EnterSessionResult(
    sessionId: Int,
    sessionType: Int = SESSION_TYPE_PRACTICE,
    result: Int = SESSION_ENTER_OK,
    flag: Int = 0,
    state: Int = 0,
) = PKT {
    Encode1(result)
    Encode1(flag)
    Encode2(sessionId)
    Encode1(sessionType)
    Encode1(state)
}

// ---- 結果碼 (case 索引 → 客戶端彈窗字串; 0 = 成功) ----
// 字串位址: 0x008A1E30 WrongPassword / 0x008A1E20 CantJoinRoom / 0x008A1E10 InvalidGrade /
//           0x008A1DCC InvalidLucci / 0x008A1DBC InvalidGachapon / 0x008A1DA8 DontHaveTMItemJoin /
//           0x008A1D90 UserRejectJoinSession / 0x008A1DEC InvalidLevel / 0x008A1614 DeferShutdown /
//           0x008A1DDC InvalidWinRate / 0x008A1DFC InvalidGradeSeason /
//           0x008A1D6C HordeEvent_RecommendBuyHordeTicket
const val SESSION_ENTER_OK = 0                 // 成功 (唯一會繼續解析房間資訊的分支)
const val SESSION_ENTER_WRONG_PASSWORD = 1     // WrongPassword     房間密碼錯
const val SESSION_ENTER_CANT_JOIN = 2          // CantJoinRoom      房間已滿 / 不可加入
const val SESSION_ENTER_INVALID_GRADE = 3      // InvalidGrade      等級不符
const val SESSION_ENTER_INVALID_LUCCI = 4      // InvalidLucci      金幣不足
const val SESSION_ENTER_INVALID_GACHAPON = 5   // InvalidGachapon   轉蛋限制
const val SESSION_ENTER_NO_TM_ITEM = 6         // DontHaveTMItemJoin 缺少入場道具
const val SESSION_ENTER_REJECTED = 7           // UserRejectJoinSession 房主拒絕
const val SESSION_ENTER_INVALID_LEVEL = 8      // InvalidLevel
const val SESSION_ENTER_DEFER_SHUTDOWN = 9     // DeferShutdown     停機倒數中
const val SESSION_ENTER_INVALID_WIN_RATE = 10  // InvalidWinRate
const val SESSION_ENTER_INVALID_SEASON = 11    // InvalidGradeSeason
const val SESSION_ENTER_HORDE_TICKET = 12      // HordeEvent_RecommendBuyHordeTicket 需買討伐券

// ---- 房間類型 (第 4 欄位) ----
// 與 C→S op 75 的第 6 欄位同源: 客戶端 0x00517233 建練習房請求時把 CFxContext[0x570] 當 E1 寫進去,
// 伺服器原樣回填到這裡。0x00516C5A 再把類型映射成規則索引: 14→3, 15→1, 0→0, 其他→2。
// 15 = 練習房 (抓包值; 對應 op 75 body 的 E1(15))
const val SESSION_TYPE_PRACTICE = 15
