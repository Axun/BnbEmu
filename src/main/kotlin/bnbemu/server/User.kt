package bnbemu.server

import bnbemu.netty.ClientSocket

class User(
    val c: ClientSocket,
    val id: Int,
    val name: String,
    val level: Int = 5,
    val lucci: Int = 1234567,
    val exp: Int = 0,
    val win: Int = 0,
    val draw: Int = 0,
    val lose: Int = 0,
    val motto: String = "PopTag Forever!",
    val favorite: String = "Bazzi"
)