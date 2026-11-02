package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.PKTHandler
import pingu.packet.decodeName

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
    // ⚠️ 不能用 DecodeStr：它會先按 ACP 轉成字串，windows-1252 對 0x81/0x8D/0x8F/0x90/0x9D
    // 等位元組是有損的，令牌會壞掉。這裡直接取原始位元組再解令牌。
    val len = Decode2
    val token = ByteArray(len).also { readBytes(it) }
    val text = decodeName(token)

    // 目前僅記錄，供後續確定 S→C 顯示 opcode 後接上廣播
    println("[ChatMsg] 收到聊天 type=$chatType 文字=\"$text\"")
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
