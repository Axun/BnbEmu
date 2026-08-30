package pingu.handler

import pingu.isJP
import pingu.netty.Decode1
import pingu.netty.Decode4
import pingu.netty.DecodeEncryptedStr
import pingu.netty.PKTHandler
import pingu.nextUserId
import pingu.packet.LoginResult
import pingu.packet.LoginResult_JP
import pingu.server.User


// client = CMainSystem::Login | TW_5 = 430A80
// server = CLoginPool::OnLogin
val LoginReq = PKTHandler { c ->
    val userCount = Decode1
    if (userCount in 1..2) {
        repeat(userCount) { i ->
            val name = DecodeEncryptedStr(0x11223344)
            val pw = DecodeEncryptedStr(0x44332211)

            val userId = nextUserId++
            val user = User(c, userId, name)
            c.users.add(user)
        }

        val unk = Decode4 // 0

        val resPacket = if (!isJP) ::LoginResult else ::LoginResult_JP
        c send resPacket(c.users)
    }
}