package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.packet.SessionList
import bnbemu.packet.UserMapInfo

/**
 * C→S 频道在线用户列表/地图同步请求 (Opcode 310)
 *
 * 逆向确认 (CA.exe, 2026-11-08):
 * 1. 位于 TSingleton<CUserMap>::RequestUserMap (0x004E02E3, push 0x136)。
 * 2. 由 CLobbyStage 发起。空载荷包。
 */
val UserMapListReq = PKTHandler { c ->
    c.send(
        UserMapInfo(),
        SessionList(),
    )
}
