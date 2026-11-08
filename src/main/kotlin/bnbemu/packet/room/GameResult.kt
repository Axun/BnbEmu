package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Room

// S→C GameResult (Opcode 150 in EN / CA.exe 0x00559854)
// 结构 (约 119 字节):
//   1. Header (11 字节, CA.exe 0x0053E09C -> CGameResultDocument)
//      Decode1: gameType   -> doc+6612  (0 = 普通对局; 非 0 = 任务/通关，见下)
//      Decode1: winnerTeam -> doc+6616  (0 = 蓝队/玩家胜, 1 = 红队/AI 胜)
//      Decode1: unk3       -> doc+6620
//      Decode1: unk4       -> doc+6624  (0 = 第 4 列画 KILLS/SAVES; 非 0 = 画 EXP/LUCCI)
//      Decode1: unk5       -> doc+6628
//      Decode4: delayMs    -> doc+6636  (2500 / 倒计时毫秒数)
//      Decode1: unk7       -> doc+6640  (0xFF / -1 跳过子结构；越界会被钳成 -1)
//      Decode1: unk8       -> doc+6644  (0xFF / -1)
//   2. 8 Slots (CA.exe 0x00541205, 每槽记录 244 字节)
//      slotType = 1 (Human) 共 63 字节字段; slotType = 2 (AI) 只读 5 字节:
//        Decode1 -> +68 结果标签帧索引, Decode1 -> +72 KILLS, Decode1 -> +76 SAVES,
//        Decode2 -> +132
//      Human 的关键字段偏移（与 0x00541205 的读取顺序一一对应）:
//        +68 结果标签帧索引 / +72 KILLS / +76 SAVES /
//        +84 EXP（备用列布局）/ +92 LUCCI（备用列布局）/
//        +124 战前等级进度 / +128 战后等级进度（EXP% 列真正的数据源）/
//        +148 带正负号的 EXP 变体（仅 feature 36 分支）/ +152 LUCCI 数字
//   3. Trailer (CA.exe 0x0053E034):
//      每 Human 槽 (slotType=1): 2 字节 (Decode1 × 2)
//
// ⚠️ 2026-11-08 修正两处字段语义（此前都是按布尔量猜的）:
//   1) gameType 必须为 0。客户端结果页装配函数 CA.exe 0x558EE1 第一句就是
//        if (*(dword *)sub_53E1B4(doc) != 0)   // doc+6612 = gameType
//            -> new CGOMissionGlyph，加载贴图 "Game/Mission"（MISSION PERFECT!! 印章）
//      也就是说 gameType=1 会让**普通练习房**的结果页顶部多盖一个 "MISSION PERFECT!!"
//      通关印章（0x44DAE3/0x44DB94）。这就是"普通练习模式不该出现 MISSION"的来源。
//   2) 每槽 +68 不是 win/loss 布尔量，而是"结果标签贴图帧索引"（0..4）：
//        CA.exe 0x46F544(列 2) -> 0x53E7CF(target, x, y, slot+68)
//      实测 0 -> "YOU WIN!!"，1 -> "PERFECT!!"。此前我们给真人写 `winnerTeam==0 ? 1 : 0`、
//      给所有 AI 槽写 0，于是**两行都落到"赢"的帧**：真人 1 = PERFECT!!、
//      电脑对手 0 = YOU WIN!!（正是截图里的现象）。
//      僵尸模式 0x5B574B 里还把它与 header.winnerTeam 做 (0,0)/(1,2) 配对比较，
//      说明它同时充当"阵营/结果"编号，绝不能当 0/1 布尔用。
//   3) EXP% / LUCCI 两列的数据源不是 +84/+92：
//      · EXP% 列（0x46F544 case 5）对真人槽走 0x53E617 = min(槽+124 + 动画进度, 槽+128)，
//        单位是千分比（1000 = 100.0%，0x53F998 里按 /10 与 %10 拆成 "xx.x" + "%"）。
//        动画进度来自 doc+6251（0x46F38F 里由结果窗计时器逐帧写入），初值 0，
//        **所以 +128 只要还是 0，这一列就永远是 00.0%**。+124 = 战前进度，+128 = 战后进度，
//        两者都 < 1000 时按 "+1000 取模" 处理升级回卷。
//      · LUCCI 列（0x46F544 case 6）读 槽+152（E4，有符号，负值会画减号）。
private const val RESULT_FRAME_WIN = 0   // "YOU WIN!!"
private const val RESULT_FRAME_LOSE = 2  // "YOU LOSE!!"（0..4 共 5 帧，2 为失去方，需实机复核）

fun GameResult(
    winnerTeam: Int = 1,
    delayMs: Int = 2500,
    expBefore: Int = 0,      // +124 战前等级进度，千分比（0..1000 = 00.0%..100.0%）
    expAfter: Int = 50,      // +128 战后等级进度，EXP% 列动画的终点（0 → 5.0%）
    lucciGained: Int = 100   // +152 本局获得金币
) = PKT {
    // 1. Header (11 字节)
    Encode1(0)           // gameType: 必须 0，非 0 会插 "MISSION PERFECT!!" 印章
    Encode1(winnerTeam)  // winnerTeam / slot
    Encode1(0)           // unk3
    Encode1(0)           // unk4 (0 = 第 4 列显示 KILLS/SAVES)
    Encode1(0)           // unk5
    Encode4(delayMs)     // delayMs
    Encode1(0xFF)        // unk7 (-1)
    Encode1(0xFF)        // unk8 (-1)

    // 2. 8 Slots
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        val isHuman = slot?.user != null
        val isAI = slot?.isAI == true
        // 该槽是否属于胜利方（真人 = 蓝队/team 0，AI = 红队/team 1）
        val won = if (isHuman) winnerTeam == 0 else winnerTeam != 0
        val frame = if (won) RESULT_FRAME_WIN else RESULT_FRAME_LOSE
        // EXP% 是千分比 0..1000，超界会被客户端当成"升级回卷"（+1000 取模），这里先钳住
        val expFrom = expBefore.coerceIn(0, 1000)
        val expTo = expAfter.coerceIn(0, 1000)

        if (isHuman) {
            Encode1(1) // slotType = 1 (Human)
            Encode4(0) // score / unk（客户端读完即丢弃）
            Encode1(frame) // +68 结果标签帧 (RESULT_FRAME_WIN / RESULT_FRAME_LOSE)
            Encode1(0) // +72 KILLS
            Encode1(0) // +76 SAVES
            Encode1(0) // +80
            Encode4(expTo) // +84 EXP（备用列布局，header unk4 != 0 时用）
            Encode1(0) // +88 exp bonus %
            Encode4(lucciGained) // +92 LUCCI（备用列布局）
            Encode1(0) // +96 lucci bonus %
            Encode4(0) // +100
            Encode1(0) // +104
            Encode4(0) // +108
            Encode1(0) // +112
            Encode1(0) // +116
            Encode1(0) // +120
            Encode2(expFrom) // +124 战前等级进度
            Encode2(expTo) // +128 战后等级进度 —— EXP% 列真正的数据源
            Encode2(0) // +132
            Encode2(0) // +136
            Encode1(0) // +140
            Encode1(0) // +240
            Encode1(0) // +241
            Encode1(0) // +242
            Encode4(0) // +144
            Encode2(0) // +148 带正负号的 EXP 变体（feature 36 分支才读）
            Encode4(lucciGained) // +152 LUCCI 列
            // (feature 6 disabled, skipped)
            Encode2(0) // +160
            Encode4(0) // +164
            Encode4(0) // +168
            Encode1(0) // +172
        } else if (isAI) {
            Encode1(2) // slotType = 2 (AI Bot)
            Encode1(frame) // +68 结果标签帧 —— 输的一方必须写 LOSE 帧，
                           // 写 0 会让电脑对手那行显示 "YOU WIN!!"
            Encode1(0) // +72 KILLS
            Encode1(0) // +76 SAVES
            Encode2(0) // +132
        } else {
            Encode1(0) // slotType = 0 (Empty)
        }
    }

    // 3. Trailer (CA.exe 0x0053E034)
    for (slotIndex in 0 until 8) {
        val slot = Room.slots.getOrNull(slotIndex)
        if (slot?.user != null) {
            Encode1(0)
            Encode1(0)
        }
    }
}
