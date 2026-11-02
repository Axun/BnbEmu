package bnbemu.packet.room

import bnbemu.isEN
import bnbemu.netty.PKT
import bnbemu.server.Bomb

// 局内炸弹状态/引爆 (S→C op 138 in EN / op 353 in TW_5)
// EN 结构 (CA.exe 0x5481BD):
//   Encode1(count) + N * [Encode2(bombId) + Encode1(state) + if (state == 0) Encode4(param)]
fun SetBombState(bombId: Int, state: Int = 0, param: Int = 0) = PKT {
    if (isEN) {
        Encode1(1)
        Encode2(bombId)
        Encode1(state)
        if (state == 0) {
            Encode4(param)
        }
    } else {
        Encode2(bombId)
        Encode1(state)
    }
}

fun SetBombStateBatch(bombIds: List<Int>, state: Int = 0, param: Int = 0) = PKT {
    Encode1(bombIds.size)
    bombIds.forEach { id ->
        Encode2(id)
        Encode1(state)
        if (state == 0) {
            Encode4(param)
        }
    }
}

fun SetBombState(bombs: List<Bomb>) = PKT {
    Encode1(bombs.size)
    bombs.forEach { bomb ->
        Encode2(bomb.id)
        Encode1(bomb.state)
        if (bomb.state == 0) {
            Encode4(0)
        }
    }
}