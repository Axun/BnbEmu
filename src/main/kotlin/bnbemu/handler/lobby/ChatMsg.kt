package bnbemu.handler.lobby

import bnbemu.debugMode
import bnbemu.domain.CharacterType
import bnbemu.netty.ClientSocket
import bnbemu.netty.Decode1
import bnbemu.netty.DecodeTokenStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.ClientStage
import bnbemu.packet.ROOM_STAGE_IDS
import bnbemu.packet.StageNotice
import bnbemu.packet.room.SlotBomber
import bnbemu.packet.user.UserDynamicInfo
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.service.PlayerService
import bnbemu.service.player

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

    // 调试命令（仅 debugMode，聊天框输入；前缀推荐 !，别用 /）：
    // ⚠️ / 开头的整行会被客户端本地吃掉：CA.exe 0x004EB22D 先判首字节 == 47 ('/')，
    //    命中就转 CChatCommand 命令表（0x008785C0：/who /w /st /start ...），
    //    查不到只弹 "NotValidCommand"，然后直接 return，**op 18 根本不会发出**。
    //    客户端只按这一个字节分流，其它任何字符都原样进 op 18，因此 ! / !! / # / @ / ~ / . 都可用。
    // 命令列表（前缀后接命令名，如 !die 3）：
    //   die  <slot>                -> 广播 S->C op159（单字节 BomberDie，只置状态17/音效0x37，实测无感）
    //   list <slot> [slot...]      -> 广播 S->C op146 state=5「死亡名单」（实测：当场死亡 + 死亡音 0x2A）
    //   trap <slot> [x y]          -> op146 state=2「困住」；必须给坐标，不给则拒绝下发（避免瞬移到左上角）
    //   free <slot> [saverSlot]    -> op146 state=12「脱困」(不给 saver) 或 state=3「被救」
    //   shield <slot>              -> op146 state=9「护盾」；被撞时客户端会自己破盾并报 op112 0xA0
    //   st   <state> <slot> [x y]  -> 广播任意 state 的 op146，用来试每个 state 的语义
    //   notice <文字>              -> 广播 S→C op22「舞台顶部公告横幅」，10 秒自动消失
    //   char <id>                  -> 强制设置当前角色（bomber id 0..31）：服务端档案/房间槽位
    //                                 与客户端 CUDI(op20)/房间头像(op105) 一起改，见 forceSetCurrentBomber
    // 详见 packet/room/SetBomberState.kt 的 state 表。
    if (debugMode) {
        // 前缀无关：剥掉行首所有非字母（! # @ ~ 等，也支持 !! 这种多字符前缀）后按命令名匹配。
        // ⚠️ 副作用：不带前缀的纯 "die 3" 也会命中，普通聊天可能误触发；建议一律带前缀。
        val cmd = text.trim().dropWhile { !it.isLetter() }

        val battle = Room.battle

        val die = DEBUG_DIE.find(cmd)
        if (die != null) battle?.debugBroadcastBomberDie(die.groupValues[1].toInt())

        val list = DEBUG_LIST.find(cmd)
        if (list != null) {
            battle?.broadcastDeathList(
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
            battle?.setBomberState(slot, state, x, y)
        }

        val trap = DEBUG_TRAP.find(cmd)
        if (trap != null) {
            val slot = trap.groupValues[1].toInt()
            val rest = trap.groupValues[2].trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            val x = rest.getOrNull(0)?.toInt() ?: -1
            val y = rest.getOrNull(1)?.toInt() ?: -1
            battle?.trapBomber(slot, x, y)
        }

        val free = DEBUG_FREE.find(cmd)
        if (free != null) {
            val slot = free.groupValues[1].toInt()
            val saver = free.groupValues[2].trim().toIntOrNull() ?: -1
            battle?.freeBomber(slot, saver)
        }

        // notice <文字> -> 广播 S→C op22 舞台顶部公告横幅（10 秒）
        // 分派与包体见 packet/StageNotice.kt；任意舞台（大厅/房间/对局/商城）都能显示。
        val notice = DEBUG_NOTICE.find(cmd)
        if (notice != null) {
            val msg = notice.groupValues[1].trim()
            if (msg.isEmpty()) {
                println("[ChatMsg] notice 命令缺少文本，已忽略")
            } else if (msg.length > MAX_NOTICE_LEN) {
                println("[ChatMsg] notice 文本过长 (${msg.length} > $MAX_NOTICE_LEN)，已忽略")
            } else {
                val pkt = StageNotice(msg)
                // 房间里有其他玩家就一起看，否则至少回给自己（单机时 Room.uniqueClients 可能为空）
                val clients = Room.uniqueClients.toList()
                if (clients.isEmpty()) c send pkt else clients.forEach { it send pkt }
                println("[ChatMsg] 下发 op22 公告 \"$msg\" 给 ${maxOf(clients.size, 1)} 条连线")
            }
        }

        // char <id> -> 强制设置当前角色（服务端 + 客户端同时落库，见 forceSetCurrentBomber）
        val charCmd = DEBUG_CHAR.find(cmd)
        if (charCmd != null) forceSetCurrentBomber(c, charCmd.groupValues[1].toInt())
    }
}

// 命令名固定不带前缀（ChatMsg 里已把行首的 ! # @ ~ 等符号剥掉；/ 前缀到不了服务端）
private val DEBUG_DIE = Regex("^die" + BLANK + "(\\d+)")
private val DEBUG_LIST = Regex("^list" + BLANK + "([\\d,\\s]+)")
private val DEBUG_ST = Regex("^st" + BLANK + "(\\d+)" + BLANK + "(\\d+)(.*)")
private val DEBUG_TRAP = Regex("^trap" + BLANK + "(\\d+)(.*)")
private val DEBUG_FREE = Regex("^free" + BLANK + "(\\d+)(.*)")
private val DEBUG_NOTICE = Regex("^notice" + BLANK + "(.+)$")
private val DEBUG_CHAR = Regex("^(?:char|bomber)" + BLANK + "(\\d+)")
private const val BLANK = "\\s+"

/** op 22 公告文本上限：客户端公告控件按单行横幅排版，过长的文本会被截断或溢出 */
private const val MAX_NOTICE_LEN = 120

/**
 * 角色 ID 上限。
 *
 * 槽位元数据里只有 6 位（`Slot.bomber` = meta[1]，客户端按 `& 0x3F` 取），但客户端真正能用的
 * 只有 0..31：`CBomberAttr` 的表来自 `Attr/BomberAttr.bin` = 32 条记录，`sub_6B426D(id)`
 * 开头就是 `if (a2 > 0x1F) return 0`（见 docs/client/character-resource-mapping.md）。
 * 32..63 塞得进槽位却查不到资源，客户端会退化成默认形象 —— 与其让人以为命令生效了，不如直接拒收。
 */
private const val MAX_BOMBER_ID = 31

/** 角色 id → 日志可读名；`CharacterType` 里没有的 id 也照样接受（调试命令不拦），只是标「未定义」 */
private fun bomberLabel(id: Int): String =
    CharacterType.entries.firstOrNull { it.value == id }?.let { "$id(${it.name})" } ?: "$id(未定义)"

/**
 * 调试命令 `char <id>`（别名 `bomber <id>`）：按角色 id 强制设置当前角色，服务端与客户端一起改。
 *
 * 「强制」= 不做规则换算、不校验持有：
 *   - 不走 `CharacterRules.resolveEffectiveBomber`（蓝蓝 4 → 进阶 10 的映射对调试是干扰，
 *     想要蓝妹妹就直接敲 10）；
 *   - 不检查玩家是否持有对应角色/进阶卡，id 在 0..31 内一律照写。
 *
 * 两端各有**两份互不相干**的存储，少写一份就会出现「服务端是 A、画面还是 B」：
 *   服务端
 *     1. `PlayerProfile.bomber` —— 走 [PlayerService.selectBomber]（只写角色 ID），之后
 *        op 60 (`cat=3` 模型) / op 20 / op 73 都按新角色生效（`cat=15` 角色卡已不再进背包/装备）；
 *     2. `Room.slots[i].bomber`（meta[1]）—— 开局名册 op 112、回房链 op 105 读的都是它。
 *   客户端
 *     3. `CUserDynamicInfo` 当前角色字段（S→C op 20 第 12 栏，CA.exe `CUDI + 3s + 111`）——
 *        客户端唯一的「当前角色」真相：op 73 上报、名片、进房链全从它取；
 *     4. 房间槽位头像（S→C op 105，2B = E1(slot)+E1(bomber)）—— 只有它会让房间界面的
 *        槽位角色**当场**换形象（客户端 `sub_4EFBF6` 读两个 Encode1 后重绘该槽）。
 *
 * ⚠️ op 20 是「整包快照」，第 8 栏就是 Lucci（`CUDI + 3s + 12`，与 op 122 同一个 setter），
 *   所以这里必须把 `player.lucci` 一起带上；发 0 会把金币清零（见 packet/UserDynamicInfo.kt）。
 * ⚠️ 不补发 op 198 / op 60：op 198 会先清空客户端背包再按 op 60 重建实际装扮；
 *   房间/对局里的形象只依赖 op 20 与 op 105，等下次进频道握手（op 81）再按新角色发 op 60 即可。
 * ⚠️ 对局中（stage 13）不发 op 105：bomber 对象在开局 op 112 时已按槽位建好，
 *   中途改 meta 不会重载模型，只会让房间界面的隐藏槽位状态和服务端不一致。
 */
private fun forceSetCurrentBomber(c: ClientSocket, bomber: Int) {
    if (bomber !in 0..MAX_BOMBER_ID) {
        println(
            "[ChatMsg] char: 角色 id 必须在 0..$MAX_BOMBER_ID" +
                "（客户端 CBomberAttr 只有 32 条记录），收到 $bomber，已忽略"
        )
        return
    }
    val player = c.player
    if (player == null) {
        println("[ChatMsg] char: 当前连线未绑定玩家档案（playerId=${c.playerId}），无法设置角色，已忽略")
        return
    }

    val before = player.bomber
    // 1) 服务端档案：只写 bomber（角色卡已不再作为背包物品下发，见 EquipSlot 注释）
    PlayerService.selectBomber(player.userId, bomber)

    // 2) 服务端房间槽位：只有真占着槽位才改，大厅期 Room.slots 可能还留着上局的残影
    val slotIdx = Room.slots.indexOfFirst {
        it.client === c || it.playerId == player.userId
    }
    if (slotIdx != -1) Room.slots[slotIdx].bomber = bomber

    // 3) 客户端 CUDI：op 20 第 12 栏（角色）+ 第 8 栏（Lucci，按档案当前值，无需再补 op 122）
    c.send(
        UserDynamicInfo(
            bomber = bomber,
            level = player.level,
            lucci = player.lucci,
            win = player.win,
            lose = player.lose,
            draw = player.draw,
        ),
    )

    // 4) 客户端房间槽位头像：只在房间类舞台发（对局中模型已建好，见函数头注释）
    val roomStage = c.stage in ROOM_STAGE_IDS
    if (slotIdx != -1 && roomStage) {
        val pkt = SlotBomber(slotIdx, bomber)
        if (Room.uniqueClients.any()) Room.BC(pkt) else c.send(pkt)
    }

    val where = when {
        slotIdx == -1 -> "不在房间槽位（仅档案+CUDI）"
        roomStage -> "房间槽位 $slotIdx meta[1]=${Room.slots[slotIdx].bomber.toInt()}，已广播 op105"
        c.stage == ClientStage.Game.value -> "对局中，op105 未下发（模型本局不变）"
        else -> "舞台 ${c.stage} 非房间，op105 未下发"
    }
    println(
        "[ChatMsg] char 强制设置角色 ${bomberLabel(before)} → ${bomberLabel(bomber)} | " +
            "服务端 bomber=${player.bomber}（装备槽 ${player.equips.size} 件不变） | " +
            "客户端 op20 bomber=$bomber lucci=${player.lucci} | $where"
    )
    if (CharacterType.entries.none { it.value == bomber }) {
        println("[ChatMsg]   ⚠️ $bomber 不在 CharacterType 表里，已按原值下发；模型/动作可能缺失")
    }
}

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
