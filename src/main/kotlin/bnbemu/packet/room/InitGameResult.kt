package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C InitGameResult (Opcode 169 in EN / CA.exe 0x0055856A)
// 结构 (15 字节):
//   Decode1: mode (1 = 似乎会触发bonus地图。胜利/结算初始化, 触发创建 CGameResultDocument 与结算 UI 窗体)
//   Decode4: unk4 (0)
//   Decode2: mapId (66 / bonus地图 ID)
//   Decode1 × 8: 8 个槽位的阵营/分组标识 (0)
fun InitGameResult(
    mode: Int = 1,
    unk4: Int = 0,
    mapId: Int = 66,
    slotTeams: IntArray = IntArray(8)
) = PKT {
    Encode1(mode)
    Encode4(unk4)
    Encode2(mapId)
    for (i in 0 until 8) {
        Encode1(slotTeams.getOrElse(i) { 0 })
    }
}
