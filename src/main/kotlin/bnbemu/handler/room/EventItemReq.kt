package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// 客户端上报的槽位事件/状态同步请求 (C->S op 149)
// 逆向定位:
// 1) CA.exe 0x00553E5F: 收到 op 136 PracticeBoard 完成棋盘初始化后发送 [slotId=-1, token=userId, action=0, param=-1] (对局加载就绪通知)
// 2) CA.exe 0x0044BD9B -> 0x0044BE31 / 0x0044BF0D: 收到 S->C op 139 时若本地未找到匹配的待确认水泡，发送 [slotId=0..7, token, action=0, param=-1] 异常上报
// 3) CA.exe 0x0054A58A / 0x0054A678 / 0x0054A6E7 / 0x0054BFF7: 炸弹/碰撞/引爆失败时的异常回退通知 (action=1, 2, 4, 3)
// 4) CA.exe 0x00549DDF: 局内道具/特殊事件上报 (action=10)
// 载荷: Encode1(slotId) + Encode4(token) + Encode1(action) + Encode4(param) = 10 字节
val EventItemReq = PKTHandler { c ->
    val slotId = Decode1
    val token = Decode4
    val action = Decode1
    val param = Decode4

    Room.onEventItem(slotId, token, action, param)
}
