package bnbemu.handler

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.DecodeTokenStr
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

/**
 * C→S op 18：大廳聊天發言（EN 客戶端在聊天框送出文字）
 *
 * 逆向依據（CA.exe 0x004EB856 的 `push 0x12` + `Init(0x12)`，實測封包 body
 * `5A 5B A5 6A D6 08 3C` 對應文字 "asf"）：
 *   body = E1(chatType) + E1(1) + DecodeStr(文字)
 *   - E1(chatType)：0 = 全體頻道（`ChatToAll`，字串鍵 0x008A0670）；1 = 隊伍
 *     （`ChatToOurTeam`）。隊伍聊天另有 C→S op 111 TeamChat。
 *   - E1(1)：觀測恆為 1，語義未定，伺服器僅跳過。
 *   - 文字欄**已套角色名令牌變換**（"asf" → `D6 08 3C`，與 op 72/81 同款 encodeName，
 *     見 packet/SessionList.kt 的 encodeName/decodeName）。
 *
 * ⚠️ 目前只實作「接收與解析」，**不回包也不廣播**，原因：
 *   S→C 的「顯示聊天」opcode 尚未確定。曾誤判為 op 18（依據 CA.exe 0x4EDBBE 的解析流程），
 *   但經全 exe 引用掃描確認該函數**沒有任何呼叫者**（死代碼），且 CStage 大廳分派鏈
 *   （0x502B38 起）也不含 0x12 分支 —— 客戶端收到 op 18 會落到默認分支直接忽略。
 *   要釘死正確 opcode 需要真實伺服器的聊天抓包。
 *
 * 另註：客戶端自己**不做本地回顯**，所以在上面的 opcode 確定之前，打字在畫面上不會有反應。
 */
val ChatMsg = PKTHandler { c ->
    val chatType = Decode1
    Decode1 // 觀測恆為 1，語義未定
    val text = DecodeTokenStr

    // 目前僅記錄，供後續確定 S→C 顯示 opcode 後接上廣播
    println("[ChatMsg] 收到聊天 type=$chatType 文字=\"$text\"")

    // 调试命令（仅 debugMode，聊天框输入，前缀 / 或 ! 都可以）：
    //   die  <slot>                -> 广播 S->C op159（单字节 BomberDie，只置状态17/音效0x37，实测无感）
    //   list <slot> [slot...]      -> 广播 S->C op146 state=5「死亡名单」（实测：当场死亡 + 死亡音 0x2A）
    //   trap <slot> [x y]          -> op146 state=2「困住」；不给坐标就用该槽最后上报的位置
    //   free <slot> [saverSlot]    -> op146 state=12「脱困」(不给 saver) 或 state=3「被救」
    //   shield <slot>              -> op146 state=9「护盾」；被撞时客户端会自己破盾并报 op112 0xA0
    //   st   <state> <slot> [x y]  -> 广播任意 state 的 op146，用来试每个 state 的语义
    // 详见 packet/room/SetBomberState.kt 的 state 表。
    if (debugMode) {
        // 前缀无关：去掉行首的非字母数字（/ ! . 等）后按命令名匹配
        val cmd = text.trim().dropWhile { !it.isLetter() }

        val die = DEBUG_DIE.find(cmd)
        if (die != null) Room.debugBroadcastBomberDie(die.groupValues[1].toInt())

        val list = DEBUG_LIST.find(cmd)
        if (list != null) {
            Room.broadcastDeathList(
                list.groupValues[1].split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.map { it.toInt() },
                debug = true
            )
        }

        val st = DEBUG_ST.find(cmd)
        if (st != null) {
            val state = st.groupValues[1].toInt()
            val slot = st.groupValues[2].toInt()
            val rest = st.groupValues[3].trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            val x = rest.getOrNull(0)?.toInt() ?: -1
            val y = rest.getOrNull(1)?.toInt() ?: -1
            // state 2/3/4 会拿这两栏当坐标：能不给就别给 0，否则客户端会把角色瞬移到左上角。
            Room.setBomberState(slot, state, x, y)
        }

        val trap = DEBUG_TRAP.find(cmd)
        if (trap != null) {
            val slot = trap.groupValues[1].toInt()
            val rest = trap.groupValues[2].trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            val x = rest.getOrNull(0)?.toInt() ?: -1
            val y = rest.getOrNull(1)?.toInt() ?: -1
            Room.trapBomber(slot, x, y)
        }

        val free = DEBUG_FREE.find(cmd)
        if (free != null) {
            val slot = free.groupValues[1].toInt()
            val saver = free.groupValues[2].trim().toIntOrNull() ?: -1
            Room.freeBomber(slot, saver)
        }
    }
}

// 命令名固定不带前缀（ChatMsg 里已把行首的 / ! 等符号剥掉）
private val DEBUG_DIE = Regex("^die" + BLANK + "(\\d+)")
private val DEBUG_LIST = Regex("^list" + BLANK + "([\\d,\\s]+)")
private val DEBUG_ST = Regex("^st" + BLANK + "(\\d+)" + BLANK + "(\\d+)(.*)")
private val DEBUG_TRAP = Regex("^trap" + BLANK + "(\\d+)(.*)")
private val DEBUG_FREE = Regex("^free" + BLANK + "(\\d+)(.*)")
private const val BLANK = "\\s+"

/*
  在大厅聊天框发送文字（实测文字 "asf"）由客户端发出 **C→S op 18 (0x12)**
  （CA.exe `0x004EB856` 的 `push 0x12` + `Init(0x12)`；原 TW/CN/JP 表把聊天放在
  op 25，真实 EN 客户端是 18）。body = `E1(chatType) + E1(1) + DecodeStr(文字)`：
  - `chatType`：0 = 全体频道（`ChatToAll`，字符串键 `0x008A0670`），1 = 队伍（`ChatToOurTeam`）；
    队伍聊天另有 C→S op 111 TeamChat。
  - 第二栏观测恒为 1，语义未定，服务器跳过。
  - **文字栏本身已套角色名令牌变换**（"asf" → `D6 08 3C`，与 op 72/81 同款 `encodeName`），
    所以服务端必须用 `decodeName`（`packet/SessionList.kt` 新增的逆变换）解回明文；
    ⚠️ 不能用 `DecodeStr`——它先按 ACP 转字符串，windows-1252 对 `0x81/0x8D/0x8F/0x90/0x9D`
    是有损的，令牌会坏。正确做法是 `Decode2` 取长度后直接 `readBytes`。
  - **客户端不做任何本地回显**，服务器不回包则玩家打出的字在画面上毫无反应。
  - ⚠️ **S→C 的「显示聊天」opcode 尚未确定**。曾误判为 op 18（依据 `0x004EDBBE` 的解析流程：
    读 DecodeStr 整行 + Decode4 会话数值 + Decode1 类型，用 `" : "`（`0x008A0414`）切分说话者/内容，
    并在 `0x004EDC75` 用 `sessionMgr->GetSessionId(0)` 比对会话数值），但经全 exe 引用扫描确认
    `0x004EDBBE` 及其所在分派器 `0x004EEB62` **没有任何调用者**（死代码），且 CStage 大厅分派链
    （`0x00502B38` 起）不含 `0x12` 分支 —— 客户端收到 op 18 会落到默认分支直接忽略。
    **需要真实服务器的聊天抓包才能钉死该 opcode**（抓包里没有聊天样本，静态分析也断了链）。
  - 当前实现：`handler/ChatMsg.kt` 只做接收与解析（解码文字并打印），不广播。
 */
