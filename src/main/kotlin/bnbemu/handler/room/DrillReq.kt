package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C→S DrillReq (EN op 124 / 0x7C) —— 局内「电钻 (Drill) 打掉面前方块」请求。
//
// 逆向结论 (CA.exe, Locale=EN / v18):
//   * 物品属性表 fx/Attr/ItemAttr 里 action 栏 (=27) 对应 `Drill` / `Item/Drill`
//     (同表相邻条目 `RemoteBomb` = 28, 与本文件下面的 case 编号互相印证)。
//   * 物品使用分派器 0x0065E4E3 按属性 action 分支:
//       case 26 → 0x004660E6 (C→S op 112 action=0x80)
//       case 27 → 0x0046636B (**本包 op 124**)
//       case 28 → 0x0046649B (遥控炸弹)
//     调用链: 按键 → 0x00461479 (校验持有量 CItemMan) → 0x65E4E3 → 0x46636B。
//   * 0x0046636B 做的事:
//       1. sub_5618B3(map, x, y, dir, &x, &y, 0) —— 沿角色朝向 trace 出**目标格**
//          (不是角色脚下, 而是面前那一格; 越界/撞不可穿透物则整包不发)
//       2. 目标格上若「可破坏方块 vtable+88()==1」或「地面障碍 sub_55E42F != null」
//          → 播本地音效 0x2C, 然后发包
//       3. **没有本地销毁、没有本地动作**: 整个函数只有 CSendPacket + Send。
//   * 载荷 3B (COutPacket 虚表 +0x20 写 1B, 与 op 106/109 同一套):
//       E1(gridX)   目标格行 (项目坐标系 0..12)
//       E1(gridY)   目标格列 (0..14)
//       E1((slotId << 4) | (dir & 0x0F))    角色槽位 << 4 | 朝向 0..3
//     实测样本 (用户日志 2026-11-18):
//       5F 5F 5B → x=5 y=5 slot=0 dir=1
//       5A 5E 5A → x=0 y=4 slot=0 dir=0
//   * ⚠️ 必须由服务端下发 S→C op 156 DrillResult 才会真的消块: 客户端收到后走
//     0x0054987D → 0x00476F31/0x0047739B (与水泡炸箱子同一对函数) 销毁方块 + 播电钻动作,
//     这一包对**所有人**都要发, 含使用者本人 (他本地没炸过)。见 packet/room/DrillResult.kt。
val DrillReq = PKTHandler { c ->
    if (readableBytes() < 3) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val gridX = Decode1
    val gridY = Decode1
    val packed = Decode1

    val slotId = packed shr 4
    val direction = packed and 0x0F

    if (debugMode) {
        println("[op124] drill: grid=($gridX,$gridY) slot=$slotId dir=$direction")
    }

    Room.battle?.onDrill(gridX, gridY, slotId, direction)

    // 防御式消费: 若将来客户端追加字段, 不让残留字节影响下一包解析
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
