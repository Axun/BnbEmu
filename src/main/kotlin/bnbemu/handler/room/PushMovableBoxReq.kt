package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C→S PushMovableBoxReq (EN op 109 / 0x6D) —— 局内「推动可移动箱子」状态上报。
//
// 逆向结论 (CA.exe, Locale=EN / v18, 复现脚本 tools/rev_op109.py):
//   * 全 .text 内 C→S op 109 只有一个发包点: 0x00539D73 (`push 0x6d` + `call 0x0064EE5D`)。
//     它位于 CGameMsgCache::OnTick (0x0053A700) 的每帧重发检查里: 遍历 10 个 0x1C 字节的
//     待确认项 `[cache+0x68] + slot*0x1C`，仅当 `+0x00 != 0` 且重试计数 `+0x18 > 5` 时发送，
//     发完立即把 `+0x00` 清 0。
//   * 该队列由 0x005393C2 填充；唯一调用者是 0x0054CABA (角色移动/碰撞时尝试推动方块:
//     0x005618B3 / 0x0055E32C / 0x0055D9C2 / 0x0055C845 / 0x0055E42F 校验格子与像素坐标
//     `* 0x28`)，再经本地帧循环 0x0054EC0D -> 0x0046D878 触发。所以它是「推箱子」而非
//     旧 TW/CN/JP 表标注的 ReadyForImmediateStartReq（立即开始/准备就绪）。
//   * 字段宽度由 COutPacket 虚表槽位钉死: `+0x20` = Encode1、`+0x28` = Encode2
//     (用同一个 `[cache+0xD8]` 包对象发出的 C→S op 110 / 0x00539BAE 交叉验证:
//      其 E1(slotId)/E1(gridX)/E1(gridY) 走 +0x20，E2(itemSeq) 走 +0x28)。故载荷共 7 字节:
//       E2(boxSeq)    [edi+0x04] 推箱子对象/序号 (0x0054CABA 里的 [obj+0x84])
//       E1(gridX)     [edi+0x08] 触发格 X (0x0054CABA 参数 [ebp+0x0C])
//       E1(gridY)     [edi+0x0C] 触发格 Y (0x0054CABA 参数 [ebp+0x10])
//       E1(targetX)   [edi+0x10] 0x005618B3 的输出参数 (箱子目标格 X)
//       E1(targetY)   [edi+0x14] 0x005618B3 的输出参数 (箱子目标格 Y)
//       E1(slotId)    推箱子的角色槽位 (0x0053A7C7 传入的帧循环下标)
//     实测样本 A5 68 5B 59 5B 5E 5A => (1, 1,3, 1,4, 0)，即 slot 0 把箱子从 (1,3) 推到 (1,4)。
//
// 服务端处理: 客户端在 0x0054CABA 里只做格子/占位校验然后 0x5393C2 入队, **没有本地位移预测**;
// 箱子位移的唯一实现是 0x00565CE0, 而它在整个 .text 里只有一个调用者 ——
// S→C op 142 的 handler 0x0054A5F9。所以服务端必须把本包 7 字节原样广播回所有人
// (含推箱者本人), 箱子才会动。广播见 Room.onMovableBoxPush / packet/room/MovableBoxMove.kt。
// ⚠️ 旧 TW_5 表的 S→C op 155 / 357 在 EN 客户端局内分发器 (0x0055A4D8) 里没有对应分支, 发了会被丢弃。
// 另注: S→C op 109 = SelectBomber (房间准备阶段) 是反方向的另一个包，与本包无关。
// 包内 slotId 是客户端 CGameMsgCache 待确认队列的槽位 (= OnTick 循环下标, 0..7), 不是玩家槽位;
// S→C op 142 的 handler 只会把它作为未使用的第 6 参数透传, 原样回放即可。
val PushMovableBoxReq = PKTHandler { c ->
    if (readableBytes() < 7) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val boxSeq = Decode2
    val gridX = Decode1
    val gridY = Decode1
    val targetX = Decode1
    val targetY = Decode1
    val slotId = Decode1

    Room.onMovableBoxPush(boxSeq, gridX, gridY, targetX, targetY, slotId)

    // 防御式消费: 若未来客户端追加字段，不让残留字节影响下一包解析
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
