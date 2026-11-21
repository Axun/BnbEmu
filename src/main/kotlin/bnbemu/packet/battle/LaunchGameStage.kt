package bnbemu.packet.battle

import bnbemu.netty.PKT
import bnbemu.tickCount

// server = CGameSession::LaunchGameStage
// client = CGameStage::OnLaunchGameStage (0x5588ba in EN, opcode 170)
fun LaunchGameStage(spawnPositions: List<Int> = emptyList()) = PKT {
    Encode4(tickCount)
    Encode1(0)
    Encode1Bool(true)
}