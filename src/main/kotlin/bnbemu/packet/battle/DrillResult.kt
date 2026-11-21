package bnbemu.packet.battle

import bnbemu.netty.PKT

// S→C DrillResult (EN op 156 / 0x9C) —— 局内「电钻 (Drill) 打掉目标格方块」广播。
//
// 逆向结论 (CA.exe, Locale=EN / v18):
//   * 局内 S→C 分发器 0x0055A4D8 的 switch 分支:
//       case 156: sub_54987D(a1, Block)      // 本包
//   * 处理函数 0x0054987D 依序读 3 字节:
//       v2 = Decode1   // 格 A (项目里的 gridX: 行, 0..12)
//       v3 = Decode1   // 格 B (项目里的 gridY: 列, 0..14)
//       v4 = Decode1   // (slot << 4) | action
//     然后:
//       sub_55EAC3(map, v2, v3)                  // 越界直接整包丢弃
//       sub_560529(map, v2, v3) → vtable+88 >= 1 → sub_476F31(tile, 0)   // 销毁可破坏方块
//       sub_55E42F(map, v2, v3) → sub_47739B(obstacle, 0)               // 连带销毁地面障碍(香蕉皮/图钉…)
//       sub_533D5C(slot) → vtable+92(action + 35)                       // 播 bomber 电钻动作
//     其中 0x00476F31 / 0x0047739B 就是水泡炸箱子的同一对函数 (前者结尾还会回 C→S op 126 上报)。
//     => 全 .text 里能从封包销毁任意格方块的分支**只有这里**, 所以它必然是本功能的 S→C 端。
//   * 载荷与 C→S op 124 (DrillReq) 逐字节同构, 服务端原样回放即可 (含使用者本人)。
//     action 低 4 位取 op 124 里的朝向 (0..3), 客户端据此挑 35..38 四套电钻动作。
fun DrillResult(
    gridX: Int,
    gridY: Int,
    slotId: Int,
    action: Int
) = PKT {
    Encode1(gridX)
    Encode1(gridY)
    Encode1(((slotId and 0x0F) shl 4) or (action and 0x0F))
}
