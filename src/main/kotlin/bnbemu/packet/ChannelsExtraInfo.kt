package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.ssdEncode
import java.io.ByteArrayOutputStream

// server = CCenterProcess::MakeChannelsExtraInfoPacket
fun ChannelsExtraInfo() = PKT {
    // EN op11 = 頻道(伺服器)列表 (StringTableRevisionReq / EnterChannelReq 均回本包, 客戶端據此顯示頻道列表)
    // 塊結構 2026-08-30 由 login.pcap sq=46 (211B) 逐字節逆向, 重編碼與抓包逐位元組一致
    // (replay_test login 用例斷言, IP 改寫位基準 cap/login_s2c_op11_ip127.bin):
    //   整包 = Encode1Bool(true) + Encode4(塊長) + SSD 資料塊
    //   塊明文 = [0x18][(塊長-5)&0xFF][u32 0][u16 頻道數 LE] + N × 頻道條目, 經 ssdEncode 變換下發
    //   塊頭規律在 op1/op2/op11/op12/op13 五個抓包塊全部吻合 (0x18 恆定, 第2字節=塊長-5,
    //   op2 1854B → 0x39 可驗); op1 條目數為 u8, op11/12/13 為 u16 LE
    //   條目 = u32 名長 + 名 + u32 人數上限 + u8 當前人數 + u8 mapNo + u32 IPv4(BE)
    //          + 14B 恆定欄位 + 3B 續讀標記 (欄位語義見 ChannelEntry)
//   條目尾 3B 標記: builder 明文恆為 00 00 00, 尾部編碼一律交給 ssdEncode;
//   末條標記恰好是塊尾 3B 餘數, 經 Encode2(0)+Encode1(0) 變成線上 a5 69 5a,
//   非末條落在 dword 組內變成線上 53 95 96 之類 — 線上形態不同, 明文都是 0
//   (客戶端 sub_75DAF0 解出的各條目標記全為 0, 舊註記「非末條解碼 ≠0 繼續讀」是誤讀)
    //   Town / SquareCompat 為特殊大地圖頻道 (bigMap=1), 與普通頻道欄位值不同, 並非被關閉
    // 改頻道名單/上限改 EN_CHANNELS 即可; 改 IP 須與 tools/patch_ip.py 同步
    val blob = ssdEncode(channelsExtraBlock(EN_CHANNELS))
    Encode1Bool(true)
    Encode4(blob.size)
    EncodeBuffer(blob)
}

// 客戶端 op11 條目中「名字 + maxCount」之後的 u8 會被讀進 CChannelCache+60,
// 由 sub_4A6ADA (0x004A6ADA) 取出, 語義是「頻道類型/分級」(也用來選 ChannelDesc%d 文案),
// 不是當前人數 (人數來自 op13 的 state):
//   50..65、70 = 排位/階梯頻道 (sub_4DC672 @0x004DC672: (u8)(a1-50)<=0xF || a1==70),
//                進這類頻道會禁用練習模式等大廳功能 — 千萬別拿它當中性值;
//   1 = 新手頻道 (sub_67CBBE), 2 = sub_67CBB1, 80 = 推薦頻道 (sub_67CBCA / sub_626EEA),
//   84/70/0 也是 sub_629E2A 的推薦目標, 0x80 = 雙人頻道 (sub_67CB61 / sub_626B4A);
//   0 = 普通自由頻道, 不命中上述任何判定。
// CNewConfig 掩碼 16 (nmconew.cfg 為空時的預設) 會啟用 flag 29, 令 sub_629FDB
//   在每次建立 CSelectServerStage 時 (sub_62A177, CA.exe.c:512908) 晚於「還原上次頁籤」
//   的 sub_6296B6 (:512609) 執行, 按玩家等級掃描 sub_629E2A(this, 1|2|70|84|0, level);
//   一旦命中就 sub_628EC6(this, 0) 強制跳回 Beginner, 選區介面銷毀時 sub_6268EB
//   再把 0 回寫 LastChannelType → 記住的頁籤被覆蓋, 下次啟動仍是新手頁。
// 抓包 Town / SquareCompat 的分級是 1, 等級 1 的玩家正好命中 sub_629E2A(this, 1, 1);
//   模擬器只把這兩條改成 0 (普通頻道), 其餘保留抓包原值 (Ranked 仍是 50 排位頻道)。
//   註: 曾把所有頻道統一成 50, 雖然頁籤記住了, 但所有大廳都被判成排位頻道 (練習模式消失)。
// 實測 (2026-11-08, 讀取執行中 CA.exe 記憶體): 玩家等級 1, 分級 [0,50,0,1,1],
//   頁籤數 3, CNewConfig 掩碼 16, LastChannelType 解碼值 1, 但選區物件 +3668 = 0。
internal const val CHANNEL_GRADE_NORMAL = 0
internal const val CHANNEL_GRADE_NOVICE = 1
internal const val CHANNEL_GRADE_LADDER = 50

// 抓包頻道 (login.pcap sq=46, 5 頻道) — op11 除 Town / SquareCompat 的分級位元組外
// 與原塊逐位元組一致 (replay_test login 用例把基準的 2 個位元組換算成 0, 基準檔本身不改)
// Town / SquareCompat 為特殊大地圖頻道 (bigMap=true), 與普通頻道欄位值不同
// op13 ChannelsState 的條目與本表一一對應 (state/enabled 欄位), 改頻道清單兩包同步生效
internal val EN_CHANNELS = listOf(
    ChannelEntry("PopTag-Forever", maxCount = 150, state = 88),
    ChannelEntry("Ranked", maxCount = 150, channelGrade = CHANNEL_GRADE_LADDER, subMapNo = 0x0A, state = 7),
    ChannelEntry("Baby-Channel", maxCount = 150, state = 7),
    ChannelEntry("Town", maxCount = 200, mapNo = 0x78, bigMap = true),
    ChannelEntry("SquareCompat", maxCount = 200, mapNo = 0x50, bigMap = true),
)

// 頻道條目 — 欄位由 op11 211B 塊逐位元組解出, 每條目 = 4 + 名長 + 27B, 重編碼與抓包逐位元組一致:
//   name      頻道名 (大廳 UI 顯示, u32 名長 + 名字)
//   maxCount  u32 人數上限 (抓包: 普通頻道 150, 大地圖 200)
//   channelGrade u8 頻道類型/分級 (sub_4A6ADA / CChannelCache+60; 詳見 CHANNEL_GRADE_NORMAL 註釋)
//                抓包 [0,50,0,1,1] → 模擬器 [0,50,0,0,0] (僅 Town / SquareCompat 1→0)
//   mapNo     u8 (普通 0x0A / Town 0x78 / Square 0x50; 推測地圖號或房間數, 語義未釘死)
//   ip        頻道伺服器 IPv4 (u32 BE) — 改動須與 tools/patch_ip.py 同步
//   subMapNo  u8 (僅 Ranked = 0x0A, 其餘 0; 語義未知)
//   bigMap    大地圖標記 (Town/Square = 1, 普通頻道 0)
//   state     op13 ChannelsState 條目的 u16 欄 (語義未釘死; 抓包: PopTag-Forever 88 /
//             Ranked 7 / Baby-Channel 7 / Town 0 / SquareCompat 0)
//   enabled   op13 條目的開關旗標
class ChannelEntry(
    val name: String,
    val maxCount: Int,
    val channelGrade: Int = CHANNEL_GRADE_NORMAL,
    val mapNo: Int = 0x0A,
    val ip: String = "127.0.0.1",
    val subMapNo: Int = 0,
    val bigMap: Boolean = false,
    val state: Int = 0,
    val enabled: Boolean = true,
) {
    fun Encode(out: ByteArrayOutputStream) {
        out.ss(name)
        out.u32le(maxCount)
        out.u8(channelGrade)
        out.u8(mapNo)
        out.ipv4(ip)
        // 14B 恆定欄位 (抓包原樣, 語義未釘死; bigMap 位於其中第 10 位元組)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(subMapNo)
        out.u8(0x00)
        out.u8(0x00)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(0xC7)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(if (bigMap) 1 else 0)
        out.u8(0x01)
        out.u8(0x00)
        out.u8(0x00)
        // 條目尾 3B 標記: 明文一律 0
        out.u8(0x00); out.u8(0x00); out.u8(0x00)
    }
}

// 塊明文: [0x18][(塊長-5)&0xFF][u32 0][u16 頻道數 LE] + N × 頻道條目 (塊頭規律見頂部註釋)
private fun channelsExtraBlock(entries: List<ChannelEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    out.u8(0x18)
    out.u8(0) // (塊長-5)&0xFF, 組裝完回填
    out.u32le(0)
    out.u16le(entries.size)
    entries.forEach { e -> e.Encode(out) }
    val ba = out.toByteArray()
    ba[1] = ((ba.size - 5) and 0xFF).toByte()
    return ba
}

// ---- 塊拼裝小工具 (塊內欄位皆為明文, 勿用會 XOR 的 Encode1/2/4 DSL; op13 共用) ----

internal fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

internal fun ByteArrayOutputStream.u16le(v: Int) {
    u8(v); u8(v shr 8)
}

internal fun ByteArrayOutputStream.u32le(v: Int) {
    u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24)
}

private fun ByteArrayOutputStream.ipv4(ip: String) = ip.split('.').forEach { u8(it.toInt()) } // BE

private fun ByteArrayOutputStream.ss(s: String) {
    val a = s.toByteArray(ACP)
    u32le(a.size)
    write(a)
}


/*

- **op 4 末字节 `channelTabCount` = 频道页签数上限（2026-11-08 逆向）**：客户端 `sub_6220A8`
  （`CLoginStage::OnPacket` `sub_625702` 的 op 4 分支 `sub_624728` 调用）解析 `UserInfo` 时把
  `E2(connectTimeoutMs)`→`[CShareContext+0x204]`、`E1`→`+0x20C`（`0x006226D1` / `0x006226E9`）；
  ⚠️ 单例 `dword_993BB0` 是 `TSingleton<CShareContext>`（0x006D01F1），旧记录写作 CUserInfo 有误。
  另：`E2(connectTimeoutMs)`=5000 是「正在连接服务器」对话框的等待超时毫秒数（sub_62B710
  比较 `timeGetTime() - 起始时间 > 该值` 触发取消），完整字段表见 `AskFirstInfoResult.kt`；
  `sub_626742`(`0x00626742`) 以 `+0x20C` 作页索引合法上限。为 0 时 `sub_6296B6` / `sub_628EC6`
  否决所有页索引，注册表 `LastChannelType` 被丢弃、默认页退回 Beginner（改注册表值无效）。
  抓包登录版 = 3、频道版 = 0；模拟器两版统一为 `CHANNEL_TAB_COUNT = 3`
  （`packet/AskFirstInfoResult.kt` / `SessionInfoResult.kt`），`tools/replay_test.py`
  的 65B 基准只豁免末字节，其余 64B 仍逐字节断言。
  ⚠️ 这只是必要条件不是充分条件：页签数正常后客户端仍会被下面的「推荐频道」逻辑拉回 Beginner。
- **op11 条目的 `channelGrade` 字节决定选区默认页是否被覆盖（2026-11-08 运行内存实测）**：
  该字节（名字 + maxCount 之后）→ `CChannelCache+60`，`sub_4A6ADA` 取出当「频道类型」
  （也用于选 `ChannelDesc%d` 文案）；当前人数其实来自 op13 的 `state`。
  ⚠️ **`sub_4DC672`（`CA.exe.c:222246`）：`(u8)(a1-50) <= 0xF || a1 == 70` = 排位/阶梯频道**，
  进这类频道会禁用练习模式等大厅功能。50 是抓包 Ranked 原值，绝不能当"中性值"用
  （曾把 5 条频道全设成 50，页签记住了但所有大厅都变成排位频道）。
  其余特殊值：`1`=新手(`sub_67CBBE`)、`2`/`70`/`84`=推荐分级、`80`=推荐频道、
  `0x80`=双人频道、`0`=普通自由频道（也被 `sub_629E2A` 当 a2=0 的目标，但该分支把
  `*(this+4870)` 设成 1，即强制 Normal 页，不会拉回 Beginner）。
  `CNewConfig` 掩码 16 启用 flag 29 → `sub_629FDB`（`sub_62A177`, `CA.exe.c:512908`）
  在**每次建立选区界面时**（晚于还原 `LastChannelType` 的 `sub_6296B6` @`:512609`）按玩家等级调用
  `sub_629E2A(this, 1|2|70|84|0, level)`；命中任一频道就 `sub_628EC6(this, 0)` 强制跳回 Beginner，
  界面销毁时 `sub_6268EB` 再把 0 回写注册表 → 保存的页签被覆盖，**改注册表值 / 改页签数都无效**。
  抓包分级 `[0,50,0,1,1]`：等级 1 命中 `a2=1` 的 Town / SquareCompat。模拟器只把这两条改成 `0`
  （`CHANNEL_GRADE_NORMAL`），Ranked 保留 `CHANNEL_GRADE_LADDER = 50`，其余不变；
  `tools/replay_test.py` 把 op11 基准的 2 个字节换算（绝对偏移 153/198），基准文件不动，其余 216B 仍逐字节断言。
  实测读取运行中 CA.exe：等级 1、分级 `[0,50,0,1,1]`、页签数 3、掩码 16、
  `LastChannelType` 解码值 1、但选区对象 `+3668 = 0` —— 与上述覆盖链一致。
 */