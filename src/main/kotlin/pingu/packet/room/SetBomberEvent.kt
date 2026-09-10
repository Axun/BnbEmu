package pingu.packet.room

import pingu.netty.PKT

// 局内角色/AI 事件广播 (S→C op 139) — tst.pcap stream4 sq=367~397 逐包 CRC8 验证
// 客户端局内每送出一个 C→S op 106 (SetBomberEventReq, 11B) 后, 服务器必须回显本包,
// 且两者严格一问一答 (客户端收到 op 139 才允许推进下一个事件 / 发下一个 slot 的 op 106)。
// body (24B) = E2(0) E1(slot) E4(slot) E4(0) E4(0) E1(gridX) E1(gridY) E1(0) E1(1) E2(slot) E2(0) E1(0)
//   前 7 栏与 op 106 body 完全一致, 中间插入 E4(0) E4(0), 尾部附 E2(slot) E2(0) E1(0)
//   (6 个样本 slot∈{1,2,3,4,5,6} 逐字节比对: 尾部 E2 值恒等于 slot)
fun SetBomberEvent(slot: Int, gridX: Int, gridY: Int) = PKT {
    Encode2(0)
    Encode1(slot)
    Encode4(slot)
    Encode4(0)
    Encode4(0)
    Encode1(gridX)
    Encode1(gridY)
    Encode1(0)
    Encode1(1)
    Encode2(slot)
    Encode2(0)
    Encode1(0)
}
