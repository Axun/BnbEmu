package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room

// S→C GameResult (Opcode 150 in EN / CA.exe 0x00559854)
// 结构 (约 119 字节):
//   1. Header (11 字节, CA.exe 0x0053E09C):
//      Decode1: gameType (1)
//      Decode1: winnerTeam (1 为 AI/红队, 0 为玩家/蓝队)
//      Decode1: unk3 (0)
//      Decode1: unk4 (0)
//      Decode1: unk5 (0)
//      Decode4: delayMs (2500 / 倒计时毫秒数)
//      Decode1: unk7 (0xFF / -1 跳过子结构)
//      Decode1: unk8 (0xFF / -1)
//   2. 8 Slots (CA.exe 0x00541205):
//      Slot 0 (玩家 slotType=1): 63 字节
//      Slot 1..7 (AI slotType=2): 每槽 5 字节
//   3. Trailer (CA.exe 0x0053E034):
//      每 Human 槽 (slotType=1): 2 字节 (Decode1 × 2)
fun GameResult(
    winnerTeam: Int = 1,
    delayMs: Int = 2500,
    expGained: Int = 50,
    lucciGained: Int = 100
) = PKT {
    // 1. Header (11 字节)
    Encode1(1)           // gameType
    Encode1(winnerTeam)  // winnerTeam / slot
    Encode1(0)           // unk3
    Encode1(0)           // unk4
    Encode1(0)           // unk5
    Encode4(delayMs)     // delayMs
    Encode1(0xFF)        // unk7 (-1)
    Encode1(0xFF)        // unk8 (-1)

    // 2. 8 Slots
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        val isHuman = slot?.user != null
        val isAI = slot?.isAI == true

        if (isHuman) {
            Encode1(1) // slotType = 1 (Human)
            Encode4(0) // score / unk
            Encode1(if (winnerTeam == 0) 1 else 0) // win/loss flag (1=Win, 0=Lose)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode4(expGained)
            Encode1(0) // exp bonus %
            Encode4(lucciGained)
            Encode1(0) // lucci bonus %
            Encode4(0)
            Encode1(0)
            Encode4(0)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode2(0)
            Encode2(0)
            Encode2(0)
            Encode2(0)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode4(0)
            Encode2(0)
            Encode4(0)
            // (feature 6 disabled, skipped)
            Encode2(0)
            Encode4(0)
            Encode4(0)
            Encode1(0)
        } else if (isAI) {
            Encode1(2) // slotType = 2 (AI Bot)
            Encode1(0)
            Encode1(0)
            Encode1(0)
            Encode2(0)
        } else {
            Encode1(0) // slotType = 0 (Empty)
        }
    }

    // 3. Trailer (CA.exe 0x0053E034)
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        if (slot?.user != null) {
            Encode1(0)
            Encode1(0)
        }
    }
}
