package pingu.packet

import pingu.netty.PKT

// ============================================================================
// S→C op 212: 特效與角色可用性配置 (進頻道握手推送 #7, seq=118, 20B)
// ============================================================================
// 逆向確認:
//   CA.exe 0x00502FD2 (CStage::OnPacket) -> 0x004FE5D5,
//   以及 0x00642717 (CSquareStage::OnPacket) -> 0x00639944.
//   處理類: TSingleton<CFxContext> (0x00993C44) 與 TSingleton<CEffectMan> (0x00993CD8).
//   欄位結構:
//     Encode4: val1 (存入 CEffectMan + 0x24)
//     Encode1: val2 (存入 0x9F10A8 + 0x10)
//     Encode2: val3 (存入 0x9F0FF8)
//     Encode4: val4 (存入 0x9F0FFC)
//     Encode1: count (存入 CFxContext + 0x630)
//     count × Encode1: 可用角色/道具索引列表 (存入 CFxContext + 0x628 vector)
//   抓包解碼: v1=0x36CEE587, v2=0, v3=22, v4=1, count=8, items=[0, 1, 2, 3, 4, 6, 8, 9]
fun FxConfig(
    val1: Int = 0x36CEE587,
    val2: Int = 0,
    val3: Int = 22,
    val4: Int = 1,
    characters: List<Int> = listOf(0, 1, 2, 3, 4, 6, 8, 9)
) = PKT {
    Encode4(val1)
    Encode1(val2)
    Encode2(val3)
    Encode4(val4)
    Encode1(characters.size)
    for (charId in characters) {
        Encode1(charId)
    }
}
