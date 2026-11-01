package pingu.handler.room

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.room.SetBomberEvent
import pingu.server.Room

// 局内角色/AI 网格位置与移动事件同步 (C→S op 106)
// 逆向定位: CA.exe 0x00638851 (CBomberSlot::OnTileStep) -> 0x00643f37 (SendPacket)
// 触发机制: 角色 (玩家 Slot 0 或 AI Slot 1~7) 在地图上移动踏入新格子或到达路径点时触发。
// 载荷结构 (11B):
//   E2(0)         固定序列号/子类型
//   E1(slotId)    角色槽位 (0=玩家, 1~7=AI)
//   E4(slotParam) 槽位参数 (与 slotId 同值)
//   E1(gridX)     当前网格 X 坐标 (0..14)
//   E1(gridY)     当前网格 Y 坐标 (0..12)
//   E1(0)         固定标志 0 (无按键/放弹状态位)
//   E1(1)         固定标志 1
//
// 真实服务器对每个 op 106 回显一个 S→C op 139 (SetBomberEvent)，且两者严格一问一答：
// 客户端收到对应 slot 的 op 139 后才会允许推进下一个事件节点 / 下一个 slot 发包。
// 缺少回显会导致客户端局内事件同步回路停摆 -> AI 僵死。
// server = CGameSession::OnSetBomberEvent
val SetBomberEventReq = PKTHandler { c ->
    val subType = Decode2
    val slotId = Decode1
    val slotParam = Decode4
    val gridX = Decode1
    val gridY = Decode1
    val flag0 = Decode1
    val flag1 = Decode1

    if (slotId in 0..7) {
        Room.slots[slotId].gridX = gridX
        Room.slots[slotId].gridY = gridY
    }

    val pkt = SetBomberEvent(slotId, gridX, gridY)
    Room.BC(pkt)
}
