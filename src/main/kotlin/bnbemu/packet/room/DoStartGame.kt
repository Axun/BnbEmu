package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.tickCount

// server = CGameSession::DoStartGame
// client = CGameStage::OnStartGame (0x558654 in EN, opcode 171)
fun DoStartGame(random: Int = 0, unk1: Int = 0, unk2: Int = 0, unk3: Int = 0) = PKT {
    if (bnbemu.isEN) {
        Encode4(tickCount)
        Encode4(unk1)
        Encode4(unk2)
        Encode1(unk3)
    } else {
        Encode4(tickCount)
        Encode4(random)
        Encode4()
        Encode4()
    }
}