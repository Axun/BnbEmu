package bnbemu.packet.room

import bnbemu.isEN
import bnbemu.isJP
import bnbemu.netty.PKT

// S→C MovableBoxMove (EN op 142 / 0x8E) —— 局内「可移动箱子位移」广播。
//
// 逆向结论 (CA.exe, Locale=EN / v18):
//   * 局内 S→C 分发器入口 0x0055A4D8 用 Decode2 读 2B opcode (0x78F090),
//     与 0xAB/0x8F/0x87 比较后进入 `sub eax,0x88` 的连续 dec/je 链:
//       136→0x556B1D  137→0x5480C1  138→0x5481BD(SetBombState)  139→0x54A3EA  140→0x54A596
//       141→0x54BD14(DetonateBomb)  142→0x54A5F9  143→0x54BE87(EatItemResult)
//     故 **op 142 就是箱子的位移通知**（旧 TW_5 表的 155 / 357 在 EN 客户端没有分支）。
//   * 处理函数 0x0054A5F9 依序 Decode2 + 5×Decode1，然后 `call 0x565CE0`
//     —— 该函数是整个 .text 里唯一移动可移动箱子的实现
//     （全文件仅 0x0054A668 一个调用者，就位于 0x0054A5F9 内）。
//   * 0x565CE0(map, idx, x1,y1, x2,y2, slot) 内部:
//       map + idx*4 + 0x19A23C 取出 CGOTileMovableBox 指针 (idx 即 C→S op 109 的 boxSeq)
//       [box+0x88]/[box+0x8C] 必须等于 (x1,y1)，否则整包作废
//       清源格 0x5606C6 + 0x5608CF(_,0)、占目标格 0x560693 + 0x5608CF(_,1)、
//       再用 vfunc +0x64 更新像素坐标。失败时客户端回 C→S op 149 做回滚上报。
//     => 服务端**必须**把收到的 C→S op 109 原样回放给所有人（含推箱者本人）；
//        0x0054CABA 只做校验 + 入队，客户端没有任何本地位移预测。
//
// 载荷 7B 与 C→S op 109 PushMovableBoxReq 逐字节同构 (实测样本 A5 68 5B 59 5B 5E 5A):
//   E2(boxSeq) E1(gridX) E1(gridY) E1(targetX) E1(targetY) E1(slotId)
fun MovableBoxMove(
    boxSeq: Int,
    gridX: Int,
    gridY: Int,
    targetX: Int,
    targetY: Int,
    slotId: Int
) = PKT {
    Encode2(boxSeq)
    Encode1(gridX)
    Encode1(gridY)
    Encode1(targetX)
    Encode1(targetY)
    Encode1(slotId)
}
