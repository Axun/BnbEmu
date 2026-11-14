package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 48: 玩家權限設定 / 功能開關 (線序 seq=43, 空體或列表體)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00502D2C (CStage::OnPacket) -> 0x004DA64B (CPermissionMan::OnPacket).
//   單例 0x009950D4: TSingleton<CPermissionMan> (虛表 0x0089DB60).
//   若載荷為空 (read_ptr >= end_ptr), 直接返回完成 (真實 EN 伺服器進頻道握手就是回空包).
//   若載荷非空, 無 count 欄位, 循環讀到 end: E4(id) + 16 × E1(flag) = 每條 20B,
//   存進 0xCC 物件 (1 個 id 槽 + 16 個 12B flag 槽, 每槽首字節是 bool, 其餘為反篡改校驗).
//   查詢 sub_4DA374 / sub_4DA4A9 以 (id, flagIndex) 精確匹配, 表空時一律回 0.
//   請求端: sub_4DA82F 清空快取並發 C→S op 39 (E1(0), 見 decoded_dump.txt sq=52).
//
// ---- 16 個特性位 (逐調用點逆向, reference/CA.exe.c) ----
//  bit  語義                                        置 1 性質
//   0   入場券/門票資格 (DontHaveEntranceTicket)     特權
//   1   名稱/等級條件豁免                             特權
//   2   等級/段位限制豁免、顯示 MPMapRank            特權
//   3   聊天刷屏/髒字/改名確認豁免 (CantChat30Sec)   特權
//   4   特殊身份/觀戰者標記、等級豁免                身份
//   5   特殊商店欄 Special08 / 角色裝飾 CharDeco      特權
//   6   聊天特權位 (與 2/3/11 並列時發 op 361)       特權
//   7   禁止帳號 ID 交換 (CantExchangeID)            ⛔ 抑制
//   8   與 bit4 成對提示 EnterObserver               身份
//   9   客戶端無常量讀取點 (保留位)                   未知
//  10   屏蔽聊天訊息與聊天視窗                       ⛔ 抑制
//  11   GM 用戶限制/封禁權限 (UP_BtUserRestrict)     特權
//  12   客戶端無常量讀取點 (保留位)                   未知
//  13   禁言 (RestrictDescRejectTalk)                ⛔ 處罰
//  14   禁止交易 (RestrictDescRejectExchange)        ⛔ 處罰
//  15   禁止進房 (RestrictDescEnterSession)          ⛔ 處罰
//  ⚠️ 13/14/15 是唯一帶 RestrictPeriodUntil 期限與 NoticeRestrict 通知的處罰位
//     (sub_62B018 雖遍歷 0..15, 描述表只註冊這三項), 全置 1 會把自己禁言/禁進房。
//     7/10 則會關掉 ID 交換與聊天視窗, 也不是特權。
//
// ---- 查表鍵 (欄位表見 AskFirstInfoResult.kt / UserDynamicInfo.kt) ----
//   CUDI +3s+87 (權限項 id, op4 #4 / op20 #11) → sub_6E5182: 登入版 1000, 頻道版 & op20 = 0
//   CUDI +3s+93 (userId, op4 #5)               → sub_6E4F0C / sub_4A69FE / sub_62B018: 7215
//   玩家物件 +96 (名冊帳號數值)                  → sub_4DA4A9 / sub_4DFA42: 7215
//   三條一起下發才能覆蓋全部查詢路徑; 抓包 op48 原為空體 (該帳號無任何特性),
//   這裡改為給固定用戶 (kcworms1 / ffdf, 7215) 下發全部正向特性。
// ============================================================================

private const val FLAG_COUNT = 16

/** 固定用戶的全部正向特性。抑制位 7/10 與處罰位 13/14/15 保持 0, 9/12 為無讀取點的保留位。 */
private val ALL_PRIVILEGE_FLAGS = setOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 11, 12)

/** CPermissionMan 的三個查表鍵: 0 = 頻道版權限項 id, 1000 = 登入版, 7215 = 固定用戶帳號數值。 */
private val PRIVILEGE_IDS = listOf(0, 1000, CAPTURE_USER_ID)

/**
 * @param ids  要賦予特性的帳號/權限項 id (客戶端按 id 精確匹配)
 * @param enabledFlags 16 個特性位中要置 1 的索引; 想連抑制/處罰位也一起開就傳 (0..15).toSet()
 */
fun PermissionInfo(
    ids: List<Int> = emptyList(),
    enabledFlags: Set<Int> = ALL_PRIVILEGE_FLAGS,
) = PKT {
    ids.forEach { id ->
        Encode4(id)
        repeat(FLAG_COUNT) { i -> Encode1(if (i in enabledFlags) 1 else 0) }
    }
}
