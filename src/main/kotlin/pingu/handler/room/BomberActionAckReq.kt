package pingu.handler.room

import pingu.netty.PKTHandler

// 局内角色动作/状态切换确认请求 (C→S op 172)
// 逆向定位:
//   CA.exe 0x00468CE8 (CBomber 状态机): 当本地角色状态机检查初始化状态 ([ebp-0x18] == 2)
//   并调用 vtable[23](0x0F) 切换角色动作到 0x0F 状态时触发。
// 载荷: 0 字节 (空载荷数据包)。
// 客户端向服务器确认其本地角色动作状态已切换 / 对局存活同步。
val BomberActionAckReq = PKTHandler { _ ->
}
