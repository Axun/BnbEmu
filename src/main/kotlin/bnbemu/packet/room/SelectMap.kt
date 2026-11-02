package bnbemu.packet.room

import bnbemu.netty.PKT

// CGameSession::OnSelectMap
fun SelectMap(map: Int) = PKT {
    Encode2(map)
}