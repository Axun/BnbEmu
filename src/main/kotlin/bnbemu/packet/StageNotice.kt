package bnbemu.packet

import bnbemu.netty.PKT

/**
 * S→C op 22：舞台消息气泡（显示 10 秒后自动消失）
 *
 * ## 逆向依据
 * **分派链**：`CStage::OnPacket` (0x00502AE1) 读出 opcode 后按 `sub eax, 0x13` 逐级递减比较：
 * ```
 * 0x502D18: sub eax, 0x13   ; eax = op - 19
 * 0x502D1B: je  0x502D85    ; op == 19
 * 0x502D1D: dec eax         ; op - 20
 * 0x502D1E: je  0x502D75    ; op == 20  → call [eax+0xEC]
 * 0x502D20: dec eax         ; op - 21
 * 0x502D21: dec eax         ; op - 22
 * 0x502D22: je  0x502D65    ; op == 22  ★ 本包
 * 0x502D6A: call [eax+0xE4] ; vtable + 228
 * ```
 *
 * **vtable +228** 在全部舞台类里都指向同一实现 `0x004FE33A`：
 * CStage(0x8A198C)、CLobbyStage(0x8A250C)、CPrepareStage(0x8A35D4)、
 * CGameStage(0x8A527C)、CShopStageNew(0x8AF0B4)——都是 `vtable+228 = 0x4FE33A`。
 * 也就是说这是各舞台共用的默认虚函数，**大厅 / 房间 / 对局 / 商城任意舞台都能收到**。
 *
 * **包体**：`0x004FE33A` 先读 `E1(kind)`：
 * | kind | 后续载荷 | 客户端行为 |
 * |------|----------|-----------|
 * | 0 | 一个 3 度混淆字符串 | 直接显示该文本 |
 * | 1 | `E4` 数值 | 套本地化键 `TournamentWillSoonBegin` 格式化 |
 * | 2 | 无 | 无参数（文本为空/沿用占位） |
 * | 3 | `E4` 数值 | 套本地化键 `AlertLosingChannelConnection_BigMonster` 格式化 |
 *
 * 字符串由 `sub_78FDF0 → sub_78F910 → sub_75DAF0` 读取，即「2B 长度 + 按 cipherDegree
 * 做 4B/2B/1B 分组 XOR」，与 `CSendPacket::EncodeCipherStr` 完全对称。
 * 最后 `sub_4FA301(stage, 0, text, 10000)` 把文本写入公告控件、显示时长固定 **10000 ms**。
 *
 * ## 形态说明：屏幕中间的气泡
 * `sub_4FA301` 写的是 `CStage +232` 指向的提示控件（显示时长写在控件 +56），
 * 呈现为**屏幕中部的气泡/提示框**，不是顶端通栏。加上 kind 1/3 走的是
 * `TournamentWillSoonBegin`、`AlertLosingChannelConnection_BigMonster` 这类**短句本地化键**，
 * 可以确认它的设计用途是「系统事件提示」，而非长文本公告。
 *
 * @param message 要显示的文本（客户端按当前代码页渲染，避免超长文本）
 */
fun StageNotice(message: String): PKT = PKT {
    Encode1(0)                // kind 0 = 自定义文本（非本地化键）
    EncodeCipherStr(message)  // 2B 长度 + 3 度混淆内容
}
