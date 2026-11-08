package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C Opcode 141
//
// 逆向定案 (CA.exe 0x54BD14, 2026-11-09 反汇编逐指令核实, 复现见 tools/rev_op107.py):
//   处理器只读 7 字节 (0x78F030=ReadUInt8 ×5 + 0x78F090=ReadUInt16 ×1):
//     E1((action << 4) | (slotId & 0x0F))
//     E2(bombId)          → sub_534338 解出 (bag, idx) 定位 CBomb
//     E1(oldX)            → 仅与 bomb+0x9C (gridX 字段) 比对做校验, 不符也照常执行
//     E1(oldY)            → 读取后丢弃
//     E1(x)               → sub_459935/sub_4598AB 的 a2, 写 bomb+0x9C (gridX)
//     E1(y)               → a3, 写 bomb+0xA8 (gridY)
//
//   action 分两路 (0x54BDFD `cmp bl,3 / ja`):
//     action 0..3 → sub_459935(bomb, x, y, action): 重定位到 (x,y), 进入 "抛掷/落地" 弹跳态
//                   (+280=1, 帧更新 sub_459EEB 播放弧线), 附带触发持泡者动作 (vtable+0x5C(action+16))
//     action 4..15 → sub_4598AB(bomb, x, y, action-4): 滑行到 (x,y), moving(+292)=1、
//                   方向(+304)=action-4, 帧更新 sub_459C69 逐帧把精灵滑到目标格
//                   —— **全 exe 只有本处理器与 op139 确认链 (sub_534C3D) 两个调用者**,
//                   客户端没有任何本地位移预测, 缺这包水泡纹丝不动。
//
//   action 高 4 位与 C→S op 107 水泡移动请求的 kind 字段一一对应 (kind=方向+4 踢出 / 0..3 触碰推行),
//   所以「踢水泡」的回应就是把对应req包体去掉末尾 E2(ms) 的前 7 字节原样以 op 141 广播。
//
fun BombKickThrow(
    slotId: Int,
    bombId: Int,
    x: Int,
    y: Int,
    oldX: Int = x,
    oldY: Int = y,
    action: Int = 0
) = PKT {
    Encode1((action shl 4) or (slotId and 0x0F))
    Encode2(bombId)
    Encode1(oldX)
    Encode1(oldY)
    Encode1(x)
    Encode1(y)
}
