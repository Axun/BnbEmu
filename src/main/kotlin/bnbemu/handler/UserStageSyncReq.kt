package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.UserMarketItemList
import bnbemu.packet.UserMarketMyItemList

/**
 * C→S 玩家舞台/特性状态同步请求 (Opcode 302)
 *
 * 逆向确认 (CA.exe, 2026-11-08):
 * 1. 位于基类 CStage::sub_4FA1AE (0x004FA1AE, push 0x12E) 及 CMyPageDialog (0x005F1AEA)。
 * 2. 客户端进入 Stage (Lobby 11 / Room 12 / Square 20) 以及个人主页 (Event/Quest) / 道具变更时发送。
 * 3. 严格受 CNewConfig 0 号全局特性开关 (sub_6C054E(0)) 控制，遍历本地玩家发送。
 * 4. 载荷为 Encode1(flag) (通常为 0)。
 * 5. 服务端收到后回复当前个人市场/状态列表 (S→C op 310 与 op 302 握手批次)。
 */
val UserStageSyncReq = PKTHandler { c ->
    Decode1
    c.send(
        UserMarketItemList(),
        UserMarketMyItemList(),
    )
}
