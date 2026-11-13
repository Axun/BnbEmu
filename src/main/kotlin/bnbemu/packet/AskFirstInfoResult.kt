package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.netty.toBA

// AskFirstInfo (C→S op 2, 登錄連線第一個業務包, op 之後跟 40B 票據資料) 的回應
// login.pcap sq=43, 65B (63B body); 內含抓包帳號 kcworms1 的身份值 — 模擬器暫無帳號體系,
// 暫以抓包值重放 (會話串另見 op7 OnConnEstablished / op254 / op297, 同串)
fun AskFirstInfoResult() = PKT {
    UserInfo(
        userId = CAPTURE_USER_ID, userId2 = CAPTURE_USER_ID, session = SESSION_STRING,
        shareContextWord = 1, permissionId = 1000, level = 1,
        gidIndex = 1, connectTimeoutMs = 5000,
        channelTabCount = CHANNEL_TAB_COUNT,
    ).Encode(this)
}

// 抓包帳號 kcworms1 的身份值 (userId 7215 = passport 0x2E 回應的賬號數值; 暫無帳號體系故用常量)
internal const val CAPTURE_USER_ID = 7215
internal val SESSION_STRING = "f0ae35f3".toBA()

// 頻道選擇頁的頁籤數上限 (op 4 末位元組 E1)。
// 逆向: CA.exe 0x006220A8 解析 op 4 時把 E2(connectTimeoutMs) 寫入 [CShareContext+0x204]、
//   E1 寫入 +0x20C (0x006226D1 / 0x006226E9); sub_626742 (0x00626742) 以 +0x20C 為頁索引合法範圍上限,
//   sub_6296B6 / sub_628EC6 據此校驗 LastChannelType 與切頁請求。
//   值為 0 時分母為 0, 所有頁索引都被判越界 → 客戶端丟棄記住的頁籤、退回 Beginner 頁。
// 注: 舊註釋寫的 "[CUserInfo+0x204]" 有誤, dword_993BB0 實為 TSingleton<CShareContext>
//   (見 sub_6D01F1 / CA.exe.c), 不是 CUserInfo。
// 抓包登入版 = 3; 頻道連線版 (SessionInfoResult) 原為 0 並會覆寫同一欄位。
// 模擬器兩版統一為 3, 避免進過普通頻道後頁籤保存失效 (replay_test 65B 基準只豁免末位元組)。
internal const val CHANNEL_TAB_COUNT = 3

// ============================================================================
// op 4 的 65B 版欄位集 (login.pcap sq=43 / enter-channel.pcap seq=85, 63B body 逐字節解出)
// ============================================================================
// 2026-11-13 反編譯逐項對齊 (31 個欄位編碼 = 32 次 Encode* 減去 1 次 EncodeBuffer，
//   ↔ 30 次無條件 Read*，另 3 次條件讀取; 無一多餘/遺漏):
//   CLoginStage::OnPacket (0x00625702) 的 op 4 分支 = sub_624728 (0x00624728)
//     → 讀 E1(結果碼), >4 走 ErrLogin* 錯誤對話框, 成功則呼叫 sub_6220A8 (0x006220A8)
//     → sub_6220A8 讀 E2(shareContextWord) + E1(本機玩家數), 與 GetLocalPlayerCount 比對,
//       相等才進 for 迴圈 (每個本機玩家一輪), 迴圈後再讀 6 個全域欄位。
//   ⚠️ 抓包用戶端有 3 段條件讀取, 本版本特性全未啟用 => 伺服器不可補發這些位元組,
//      否則污染後續欄位對齊:
//      · sub_6C054E(6)   → 每個本機玩家 2×E2 (sub_4F9567 / sub_4F954D)
//      · sub_6C05A2(1)   → NeedToChangePassword E1 (channelTabCount 之後)
//      · sub_6C054E(102) → 呼叫 sub_6220A8 **之前** 2×E1 (sub_6873BF / sub_6873CA, 0x00625324)
//      另: 結果碼 >4 的錯誤分支內還有 4 次 E1 (各 ErrLogin* 附帶欄位), 成功路徑不走。
//
//  #  編碼        登入版值   客戶端落點 (setter)                        語義
//  1  E1(0)       0         sub_624728 開頭分支                      登錄結果碼 (0=成功; 5/9/10/11 → ErrLoginCheckNexonId 等)
//  2  E2(scWord)  1         sub_6D01CA → CShareContext+0x100 (WORD)  全 .text 無讀取點：只寫不讀的保留欄位/版本字
//                                                                     (參數名 shareContextWord)
//  3  E1(1)       1         sub_6220A8 與 GetLocalPlayerCount 比對    伺服器確認的本機玩家數, 不等即 sub_68D75F 報錯
//  4  E4(permId)  1000      sub_6E4B51 → CUDI +3s+87                 權限項 id (與 op20 第 11 項同欄位)。唯一讀取方是
//                                                                     sub_6E5182 → sub_4DA374(CPermissionMan, 特性號, 值),
//                                                                     即拿它去權限表按 id 匹配, 不是裝備/道具 id;
//                                                                     表條目由 op48 E4(permissionId)+16×E1(flags) 建立
//  5  E4(userId)  7215      sub_6E4B6B 參1 → CUDI +3s+93              帳號數值在 CUserDynamicInfo 的鏡像;
//                                                                     同值亦當 CPermissionMan 表 id 用
//                                                                     (sub_4A69FE 拿它查特性 13)
//  6  E2(0)       0         sub_6E4B6B 參2 → CUDI +3s+99              **權限到期時間第 1 分量**: 與 #5 由
//                                                                     sub_6E4EC4 成組讀出, 再經 sub_6295C9 用
//                                                                     "RestrictPeriodUntil" 格式串 sprintf
//  7  E2(0)       0         sub_6E4B6B 參3 → CUDI +3s+105             同上第 2 分量 (皆 0 = 無期限)
//  8  E4(0)       0         sub_6E4C0F → CUDI +3s+144                 **特性 0 免檢標誌 (bit0)**: sub_6E4F6D 開頭
//                                                                     即 `if (a2 != 0) return 0` — 只有查特性 0
//                                                                     才看它; 逐槽 (<1..2>) 全置位才讓 sub_6E5182
//                                                                     短路回 1, 其餘特性仍走 CPermissionMan
//                                                                     ⚠️ 不是「全特性免檢」, 不能代替 op48
//                                                                     另: S→C op 232 = E4(槽)+E4(值) 可單獨設
//  9  E4(userId2) 7215      sub_6CEFCA → VCSelfInfo +s+80              帳號數值 (passport 0x2E 同值)
// 10  E2(len)+buf "f0ae35f3" sub_78FE10 → sub_6CEFEF → VCSelfInfo +16s+24 (std::string)
//                                                                     會話/帳號標識串; sub_6CEFDC 讀出後作
//                                                                     註冊表鍵 "%sDeny%dWhisperId%d" 的 %s 前綴
// 11  E1(0)       0         sub_6CF196 → VCSelfInfo +3s+74             全 .text 未見讀取點 (僅寫入), 填 0 即可
// 12  E2(0)       空字串    sub_78FE10 → sub_6CF026 → VCSelfInfo +16s+512 (std::string)
//                                                                     未見讀取點 (僅寫入); ⚠️ 不是整數 0：
//                                                                     按「長度 0 的字串」解碼, 位元組同 Encode2(0)
// 13  E4(0)       0         sub_6E4AC7 → CUDI +3s+69 / +63              複合槽位 id = 24×大類+子槽 (op20 第 9 項)
// 14  E1(0)       0         sub_61C352 → CUDI +3s+75                   僅登出/重置路徑讀 (CA.exe.c:469887 /
//                                                                     :477932): 非 0 才 sub_67EDF7 清標誌
// 15  E2(level)   1         sub_6E4AAD → CUDI +3s+6                    **玩家等級** (op20 第 1 項 / GameResult +136 同欄位)
// 16  E1(0)       0         sub_6E473B → CLadderInfo +3s+7               天梯段位 (op20 第 2 項)
// 17  E1(0)       0         sub_6E478F → CLadderInfo +3s+25              天梯數值 E (op20 第 4 項)
// 18  E1(0)×3     0,0,0     sub_6E4C72/C8C/CA6 → CUDI +3s+36/42/48      勝場 / 負場 / 平局 (op20 第 20~22 項, GameResult 已釘死)
// 19  E4(0)       0         sub_6CF1B0 → VCSelfInfo +3s+82              **玩家槽有效標誌**: sub_6CF40E 就是本欄
//                                                                     位 getter (讀 +328B); 寫完立刻讀回, 為 0
//                                                                     則 v77=0, 循環後 v77 → VCSelfInfo+144。
//                                                                     另參與 UI 分支 (MenuDlg_Back/_3) 與
//                                                                     C→S op332 / op351 上報
// 20  E1(0)×2     0,0       sub_6CF209 / sub_6CF223 → VCSelfInfo +3s+147/+153  兩者均未見讀取點
//                                                                     (sub_44862D +588/+612 無命中), 填 0
// 21  E4(0)       0         sub_6CF1EF → VCSelfInfo +3s+107             **AuthenCode 認證碼**: CA.exe.c:431296
//                                                                     存檔 XML 節點 "AuthenCode" (getter
//                                                                     sub_6CF488)
// 22  E1(0)       0         sub_6213F5 → VCSelfInfo +3s+119             被 sub_448F1A (CA.exe.c:63398) 複製進屬性
//                                                                     容器 (sub_448424), 未見其他讀取點
// 23  E1(gidIndex) 1         CShareContext +292 (0x124)                  **GID 槽號 (1-based)**：sub_67FD98 (0x67FDE0 處)
//                                                                     取值 <1 鉗制為 1, 再以 (值-1) 拼 "LastSelectedGID%d";
//                                                                     sub_680014 同值拼 "%sDeny%dWhisperId%d"
// 24  E1(0)       0         CShareContext +296 (0x128)                  本機設定來源開關, 唯一讀取點
//                                                                     CA.exe.c:557176 / sub_683323: !=0 讀註冊表
//                                                                     (sub_67E4B0/sub_6D6B32), ==0 發 WM 0x403
// 25  E1(0)       0         sub_4484B0 → CShareContext +360 (0x168)      功能開關, 唯一讀取點 CA.exe.c:432453
//                                                                     (stage==14 且該值非 0 才走某分支)
// 26  E1(0)       0         CShareContext +372 (0x174)                  功能開關 (byte), 讀取點 CA.exe.c:199970
//                                                                     / :307703, 均與本機玩家數 > 0 搭配
// 27  E2(connMs)  5000      CShareContext +516 (0x204, WORD)             **「正在連線伺服器」等待逾時 (ms)**：
//                                                                     選服/等待連線 stage 構造 (CA.exe.c:478578) 複製到 this+744,
//                                                                     sub_62B710 以 `timeGetTime() - *(this+187) > 該值`
//                                                                     觸發 vtable+160 的取消動作 (抓包 5000 = 5 秒)
// 28  E1(tab)     3         CShareContext +524 (0x20C)                  **頻道頁籤數上限**, 詳見 CHANNEL_TAB_COUNT
//  (CUDI = CUserDynamicInfo 單例 dword_993E70; CLadderInfo = dword_9982D0;
//   VCSelfInfo = dword_993C40; CShareContext = dword_993BB0; s = 本機玩家槽; 偏移單位見各 setter)
//
// 語義已釘死: userId/userId2 = 賬號數值 (passport 0x2E 同值; 登入版兩處皆 7215, 頻道版僅
//   userId2 保留 7215、userId 為 0), session = 4B 會話串 (兩變體同串)。
//   其餘數值欄僅登入版有值 (1000 / 1 / 1 / 5000), 頻道版全 0；13B 變體 (UserSimpleInfoResult)
//   結構不同, 不走本類。
// ⚠️ #4/#5 是 CPermissionMan 的鍵: 客戶端拿它查 op48 建立的權限表 (16 個特性 flag)。op48 空體時
//   任何非 0 值都匹配不到 -> 相關特性全部視為關閉; 要開功能必須 op48 與此處 id 對上。
//   #8 的免檢只對特性 0 生效 (其餘特性 sub_6E4F6D 直接返回 0), 不提供任何具體特性。
class UserInfo(
    val userId: Int = 0,
    val userId2: Int = 0,
    val session: ByteArray = ByteArray(0),
    // sub_6D01CA → CShareContext+0x100；EN 用戶端全 .text 無讀取點，屬只寫不讀的保留欄位
    val shareContextWord: Int = 0,
    // sub_6E4B51 → CUserDynamicInfo +3s+87（op20 第 11 項同欄位）。
    // 該值唯一的讀取方 sub_6E5182 拿它去 CPermissionMan (dword_9950D4) 的權限表按 id 匹配
    // (sub_4DA374), 表條目由 op48 E4(permissionId)+16×E1(flags) 建立 => 是權限項 id,
    // 不是裝備/道具 id。⚠️ op48 目前發空體, 任何非 0 值都匹配不到 (= 特性全關)。
    val permissionId: Int = 0,
    // sub_6E4AAD → CUserDynamicInfo +3s+6，玩家等級
    val level: Int = 0,
    // CShareContext+0x124，1-based GID 槽號（本機註冊表鍵索引）
    val gidIndex: Int = 0,
    // CShareContext+0x204，CWaitForConnSvrDlg 等待逾時毫秒數
    val connectTimeoutMs: Int = 0,
    val channelTabCount: Int = CHANNEL_TAB_COUNT,
) {
    fun Encode(p: SendPacketBase) {
        p.Encode1(0)
        p.Encode2(shareContextWord)
        p.Encode1(1)
        p.Encode4(permissionId)
        p.Encode4(userId)
        p.Encode2(0)
        p.Encode2(0)
        p.Encode4(0)
        p.Encode4(userId2)
        p.Encode2(session.size)
        p.EncodeBuffer(session)
        p.Encode1(0)
        p.Encode2(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode2(level)
        repeat(5) { p.Encode1(0) }
        p.Encode4(0)
        p.Encode1(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode1(gidIndex)
        repeat(3) { p.Encode1(0) }
        p.Encode2(connectTimeoutMs)
        p.Encode1(channelTabCount)
    }
}
