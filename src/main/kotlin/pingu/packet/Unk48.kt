package pingu.packet

import pingu.netty.PKT

// op 7 OnConnEstablished 回應序列 #1 (enter-channel.pcap +0.305s)
// payload 僅 2B opcode, 空體 —— AGENTS.md: "op 48 是 op 7 後的空體回應"
fun Unk48() = PKT {
}
