package pingu.handler

import pingu.netty.PKTHandler

// 练习房游戏进行中客户端周期性发送的空包 (tst.pcap stream4 sq=118, 空载荷)。
// 客户端向服务器确认其仍在游戏内 / 同步节奏; 真实服务器不逐包回应 (AI 行动由服务器周期广播)。
// 绑定空 handler 以消费载荷、消除"找不到 Handler"噪音。
// server = C???::OnItemThrowAck (原始名称未知, 绑定见 EN_0_recv.yaml)
val Unk_172 = PKTHandler { _ ->
}
