package bnbemu.handler

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.netty.readTokenBytes
import bnbemu.packet.decodeName

/**
 * C→S op 297：登记本端会话密钥（RegisterSessionKey）
 *
 * body = `EncodeStr(4B 会话密钥) + Encode1(kind)`，服务器**从不回应** → fire-and-forget。
 * 抓包实测 `A5 6D F0 AE 35 F3 5B` = 串长 4 + 密钥 `F0 AE 35 F3` + kind 1。
 *
 * ## 密钥是什么
 * 就是 op 4 (65B) / op 7 / op 254 里那枚 4B 会话密钥（本项目既有注释里叫「会话令牌/会话串」）。
 * 注意它**经过角色名令牌变换**：EncodeStr 在 cipherDegree 1..3 时走 CA.exe `0x0078F540`
 * → `0x0075D940`（4B 一块：LE 读 ^0x96CA5395 按 BE 写），所以线上 `F0 AE 35 F3` 的明文是
 * `66 66 64 66` = `"ffdf"`（4 个 hex 字符）。客户端收到 op 4 时解码存进 CSelfInfo，
 * 需要时再编码发回，因此服务端原样回放抓包字节即可闭环。
 *
 * ## 为什么发（CA.exe 逆向，见 tools/rev_op297.py）
 * 全 `.text` 只有 3 处构造 op 0x129，分属两个发送者：
 *   1. `0x006DA5A7`（kind **恒为 1**）：取 `TSingleton<CSelfInfo>`（单例 `0x00993C40`，
 *      vtable `0x008BC6BC`）里的会话条数 `0x006CF33B`，再逐条 `0x006CEFDC`
 *      （元素 = `this+0x18+i*16`）发一包 → **每枚会话一包**，正常只 1 枚。
 *      调用者只有两处：
 *        · `0x006DB43F`（内层 `0x006DB453`，仅当 `0x0079D604` 建舞台成功才发）：
 *          客户端每切进一个新舞台/界面（大厅、房间、商城…）就重登一次，实测「进频道后 +0.5s」
 *          「练习房 +3.7s」「开始对局 +6.7s」各一包；
 *        · `0x006E0BC6`（函数 `0x006DFD1D` 尾部，`cmp edx, 0x493E0`）：与上次相隔
 *          **300000ms = 5 分钟**就重发 → 周期保活。
 *   2. `0x004AFD1C`（kind **恒为 0**）：Nexon Messenger 对话框 `TSingleton<CSmsDlg>`
 *      （vtable `0x00899EC0`）的方法，发自己 `this+0x1F8C` 那枚密钥；由两个 S→C 处理器
 *      `0x004AFDE0` / `0x004AFDF7`（各自先 Decode1 再转发）触发。姊妹包 op 299 (`0x12B`) =
 *      同对象、只发密钥不带 kind。
 *
 * ## 结论
 * 语义 = 「告诉服务器：我这边持有的会话密钥是 X（来源 kind）」——进舞台时重登 + 5 分钟续命 +
 * 信使侧应答。属于会话/在线状态的登记包，与好友邀请、信使寻址共用这套密钥。
 * 服务端无需回包、也无需校验（真实服务器同样沉默），此处只解析 + 调试日志。
 */
val RegisterSessionKey = PKTHandler { _ ->
    // 防御式消费：残包不允许抛异常（断线），读不到就按空处理。
    val token = readTokenBytes()
    val key = if (token.isEmpty()) "" else decodeName(token)
    val kind = if (readableBytes() >= 1) Decode1 else 0

    if (debugMode) println("[RegisterSessionKey] key=$key(${token.toHex()}) kind=$kind")
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
