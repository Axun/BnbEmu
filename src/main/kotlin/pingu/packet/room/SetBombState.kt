package pingu.packet.room

import pingu.isJP
import pingu.netty.PKT
import pingu.server.Bomb

fun SetBombState(bombs: List<Bomb>) = PKT {
    Encode1(bombs.size)
    bombs.forEach { bomb ->
        Encode2(bomb.id)
        Encode1(bomb.state) // 0 = 爆炸 1 = MovingStop?
        if (isJP && bomb.state == 0) {
            Encode4()
        }
    }
}