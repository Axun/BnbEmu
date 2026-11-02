package bnbemu.packet.room

import bnbemu.netty.PKT

// 局内角色/AI 放置水泡确认广播 (S→C op 139, CA.exe 0x0054A3EA) — tst.pcap stream4 sq=367~397 逐包 CRC8 验证
// 客户端局内每送出一个 C→S op 106 (SetBomberEventReq, 11B) 后, 服务器必须回显本包,
// 且两者严格一问一答 (客户端收到 op 139 才完成待确认水泡的 bombToken 匹配与 bombId 赋值)。
// body (24B) = E2(subType) E1(slot) E4(bombToken) E4(0) E4(0) E1(gridX) E1(gridY) E1(flag0) E1(flag1) E2(bombId) E2(0) E1(0)
//   - bombToken (第3栏 E4): 必须原样回显客户端 op 106 传来的 bombToken (slotId + 8*k), 供 CA.exe 0x0044BE05 匹配待确认水泡
//   - bombId    (第10栏 E2): 服务端分配的水泡 ID (真实服直接取 bombToken), 供 CA.exe 0x0045976E 写入 bomb+0x90,
//                            后续 S→C op 138 SetBombState(bombId, state=0) 引爆时按此 ID 查找水泡 (0x00534338)
fun SetBomberEvent(
    slot: Int,
    gridX: Int,
    gridY: Int,
    bombToken: Int = slot,
    bombId: Int = bombToken,
    subType: Int = 0,
    flag0: Int = 0,
    flag1: Int = 1
) = PKT {
    Encode2(subType)
    Encode1(slot)
    Encode4(bombToken)
    Encode4(0)
    Encode4(0)
    Encode1(gridX)
    Encode1(gridY)
    Encode1(flag0)
    Encode1(flag1)
    Encode2(bombId)
    Encode2(0)
    Encode1(0)
}
