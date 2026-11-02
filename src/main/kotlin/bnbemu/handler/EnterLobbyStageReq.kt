package bnbemu.handler

import bnbemu.netty.PKTHandler

import bnbemu.packet.EnterLobbyStage

// CChannelPool::OnEnterLobbyStage
val EnterLobbyStageReq = PKTHandler { c ->
    c send EnterLobbyStage()
}