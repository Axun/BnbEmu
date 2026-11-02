package bnbemu

import bnbemu.netty.NettyServer

var nextUserId = 1

fun main() {
    NettyServer.start()
}