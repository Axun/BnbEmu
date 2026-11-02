package bnbemu.handler.room

import bnbemu.netty.PKTHandler
import bnbemu.packet.room.StartGameResult
import bnbemu.server.Room

// server = CGameSession::OnRequestStartGame
val StartGameReq = PKTHandler { c ->
    val res = 0
    c send StartGameResult(res)

    Room.startGame()
}