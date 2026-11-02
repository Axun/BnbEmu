package pingu.handler

import pingu.netty.Decode1
import pingu.netty.Decode2
import pingu.netty.Decode4
import pingu.netty.DecodeStr
import pingu.netty.PKTHandler
import pingu.packet.CreateSessionResult
import pingu.server.*

// 開房間 / 退出商城
// server = CGameSessionTable::CreateSession
val CreateSessionReq = PKTHandler { c ->
    if (readableBytes() == 6) {
        // CShopStageNew 0x005CDD3D 退出商城通知 (E2 + E4)
        val channelId = Decode2
        val extra = Decode4
        return@PKTHandler
    }

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
