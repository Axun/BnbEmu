package bnbemu.handler

import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler

import bnbemu.packet.ResEstimateRTT
import bnbemu.tickCount

//  server = CUserPool::OnEstimateRTT
val EstimateRTT = PKTHandler { c ->
    val sequence = Decode4

    c send ResEstimateRTT(sequence, tickCount)
}