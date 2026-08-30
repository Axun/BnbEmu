package pingu.handler

import pingu.netty.Decode1
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler
import pingu.packet.CreateSessionResult
import pingu.server.*

// 開房間
// server = CGameSessionTable::CreateSession
val CreateSessionReq = PKTHandler { c ->
    val roomName = DecodeStr
    val pw = DecodeStr
    val mode = Decode1 // FREE = 0 | MANNER = 1
    val userCount = Decode1

    repeat(userCount) { i ->
        Decode1 // 11

        Room.slots[i].apply {
            user = c.users[i]

            state = FLAG_CHIEF
            bomber = 5
            color = i
        }
    }

    c send CreateSessionResult(mode)
    Room.encodeSlots(c)
}