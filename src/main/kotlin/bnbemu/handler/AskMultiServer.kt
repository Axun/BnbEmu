package bnbemu.handler

import bnbemu.isJP
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.MultiServerInit

// server = CLoginPool::OnAskMultiServer
val AskMultiServer = PKTHandler { c ->
    val multisvr_HashValue = Decode4
    if (!isJP) {
        val multisvrn_HashValue = Decode4
    }

    c send MultiServerInit()

    // NA_12 -> 192 | 2 | 67 | 61 | 1 | 51
}