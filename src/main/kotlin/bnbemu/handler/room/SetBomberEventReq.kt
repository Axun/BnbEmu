package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SetBomberEvent
import bnbemu.server.Room

// 局内角色/AI 放置水泡请求 (C→S op 106)
// 逆向定位: CA.exe 0x006375A1 / 0x00534CCA -> CBombBag::0x0044C056 -> CGameMsgCache::0x005391AB -> 0x00539A4B (push 0x6a)
// 触发机制: 角色 (玩家 Slot 0 或 AI Slot 1~7) 放置水泡时，先在本地分配待确认水泡并生成全局唯一 bombToken (slotId + 8*k)，随后发出本包。
// 载荷结构 (11B):
//   E2(subType)   子类型 (恒为 0)
//   E1(slotId)    角色槽位 (0=玩家, 1~7=AI)
//   E4(bombToken) 客户端生成的待确认水泡令牌 (0x0044BFE0: 初始为 slotId, 该槽位每放一颗泡 +8)
//   E1(gridX)     水泡所在行坐标 (0..12)
//   E1(gridY)     水泡所在列坐标 (0..14)
//   E1(flag0)     特殊水泡标志 (0=普通)
//   E1(flag1)     水泡威力与标志位 (低 4 位 = 水泡威力 power)
//
// 服务端处理:
//   1) 调用 Room.addBomb 注册水泡 2.5s 倒计时 (到期后由 Room.onTick 广播 S→C op 138 SetBombState(bombId, 0) 与 op 148 引爆);
//   2) 回显 S→C op 139 (SetBomberEvent)，原样带回 bombToken 及服务端分配的 bombId，
//      客户端在 0x0054A3EA -> 0x0044BD9B 匹配 bombToken 后将 bombId 写入水泡对象 (+0x90) 并扣减待确认计数。
// server = CGameSession::OnSetBomberEvent
val SetBomberEventReq = PKTHandler { c ->
    val subType = Decode2
    val slotId = Decode1
    val bombToken = Decode4
    val gridX = Decode1
    val gridY = Decode1
    val flag0 = Decode1
    val flag1 = Decode1

    if (slotId in 0..7) {
        Room.slots[slotId].gridX = gridX
        Room.slots[slotId].gridY = gridY
    }

    val bombId = Room.addBomb(
        slotId = slotId,
        gridX = gridX,
        gridY = gridY,
        isSpecial = (flag0 != 0),
        bombId = bombToken,
        broadcastInitialState = false
    )

    val pkt = SetBomberEvent(
        slot = slotId,
        gridX = gridX,
        gridY = gridY,
        bombToken = bombToken,
        bombId = bombId,
        subType = subType,
        flag0 = flag0,
        flag1 = flag1
    )
    Room.BC(pkt)
}
