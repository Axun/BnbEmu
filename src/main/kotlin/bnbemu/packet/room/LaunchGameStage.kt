package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.tickCount

// server = CGameSession::LaunchGameStage
// client = CGameStage::OnLaunchGameStage (0x5588ba in EN, opcode 170)
fun LaunchGameStage(spawnPositions: List<Int> = emptyList()) = PKT {
    if (bnbemu.isEN) {
        Encode4(tickCount)
        Encode1(0)
        Encode1Bool(true)
    } else {
        Encode4(tickCount)

        Encode2(Room.mapId)
        // 台版 87 = 賽車場05 | 89 = 賽車場07 | 156 = 海14 | 188 = 村10
        // 日版 118 = 海14

        Encode1(Room.activeSlotCount)
        Room.slots.forEachIndexed { index, slot ->
            if (slot.user != null || slot.isAI) {
                Encode1(index) // slotId
                Encode1Bool() //  隊長 好像是公會戰用的
                Encode1(spawnPositions.getOrElse(index) { index }) // 角色開始的位置
                Encode1(slot.bomber) // 人物
                Encode1(slot.color) // 隊伍顏色
            }
        }
    }
}