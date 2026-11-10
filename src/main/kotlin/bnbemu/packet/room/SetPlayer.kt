package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.packet.CAPTURE_USER_ID
import bnbemu.packet.ItemDef
import bnbemu.packet.ItemDefEntry
import bnbemu.packet.SESSION_STRING
import bnbemu.server.EquipSlot
import bnbemu.server.Slot
import bnbemu.server.User

// ============================================================================
// S→C op 100: SetPlayer (CSetPlayerOnPreparePacket)
// 官方内部名 MakeSetPlayerPacket, 派生自 CSetPlayerPacket + CSlotAccessoryPacket。
// 客户端处理入口: CSlotStage::OnPacket (0x004F480C) / CPrepareStage 父类 sub_4F5924
// 分发到虚表 +404 (sub_4F7022)，通过 sub_4DFCE7 解析槽位玩家 CUser 对象。
//
// 帧结构 (78 字节 / 游戏开始版 77 字节):
//   1. CSetPlayerPacket (13B / 12B):
//      Encode1(slot) + Encode4(userId) + Encode1(flag16) + Encode1(flag20=0) +
//      Encode1(flag36=0) + Encode1(dummy=0) + [if (flag16 != 0) Encode1(enterEffectId=255)] +
//      EncodeBuffer(raw3[3])
//   2. CUser::Decode (48B):
//      Encode2(nameLen) + nameBuffer (session 串) +
//      Encode1(v1=0) + Encode1(v2=0) + Encode4(v3=0) + Encode1(v4=0) + Encode4(v5=0) +
//      Encode2(level) + Encode4(v7=0) + Encode4(v8=0) + Encode4(v9=0) + Encode4(v10=0) +
//      Encode1(v11=0) + Encode4(v12=0) + Encode2(s1=0) + Encode2(s2=0) + Encode2(s3=0) + Encode2(s4=0)
//   3. Tail of CSetPlayerPacket (6B):
//      Encode4(tail_u32=0) + Encode1(tail_f40=8) + Encode1(tail_f44=0)
//   4. CSlotAccessoryPacket (11B + 各装扮清单):
//      Encode2(acc_u16) +
//      Encode1(count1) + count1 × [Encode1(idx) + Encode2(value)] +
//      Encode1(count2) + count2 × [Encode4 + Encode4 + Encode4] +
//      Encode1(acc_f29=255) + Encode1(acc_f30=0) + Encode4(acc_f32=水泡皮肤编号) +
//      Encode1(count3) + count3 × [Encode4 + Encode4 + Encode4]
//
// ---------------------------------------------------------------------------
// CSlotAccessoryPacket 三段清单 = 玩家装备快照 (2026-11-09 反编译钉死)
// ---------------------------------------------------------------------------
// 反序列化: sub_44A856 (0x0044A856)。注意旧实现把 count2 写在 count1 之后、
// f29 之前, 只有三个 count 全为 0 时才与真实线序逐字节相同; 一旦带装扮就会错位。
//
// ⚠️ 客户端收到 op 100 后会**先清空**该槽位的全部装扮, 再仅凭这三段清单重建:
//   - sub_4F6E89 (0x004F6E89, 虚表 +420/+424):
//       for i in 0..8  sub_687455(..., i, 0)   ← 清 9 个装扮槽
//       逐条 count1 → sub_687455 + sub_4D59FA(slotUI, idx, value)
//       for j in 0..8  sub_687217(..., -1, j, -1) ← 清 9 个特效槽
//       逐条 count2 → sub_687217 + sub_4D59EF(slotUI, a, b, c)
//   - sub_4F6B51 (0x004F6B51):
//       sub_679271(CAvatarContextGame, slot)  ← 清 12 个纸娃娃部位
//       逐条 count3 → sub_6792AB → CGOAvatar::sub_45901F
//   ⇒ 三段全发 0 不是"没有装扮", 而是命令客户端「把背景/旗帜/特效全部脱掉」。
//     这就是 C→S op 273 已经上报 `slot=0xDC(220) item=710418` 却仍看不到背景的根因。
//
// 三段清单的字段含义 (客户端 0x004F1780 附近按 category 分支的同款映射, 见 240389~240422):
//   - count1 ← category 10 (Item/ShopDeco 背景/旗帜/边框):
//       (idx = ItemDef.targetCharId, value = ItemDef.skinId = 背景序号)
//       例: 710418 "Slot background 418" → targetCharId=1, skinId=418
//       ⇒ 条目 0x01 / 0x01A2; 复合槽位 = 24×9 + (targetCharId+3) = 220 (sub_6580F2)
//       清槽后 sub_4D59FA 只接受 idx <= 8 (0x004D59FA)。
//   - count2 ← category 12 (Item/CharDeco 角色特效):
//       (skinId, targetCharId, param), 客户端校验 (skinId+2)<=9 / targetCharId<=8 / param>=0。
//   - count3 ← category 19 (纸娃娃装扮):
//       (skinId, targetCharId, param), CGOAvatar::sub_458CD3 只在 param > 0 时生效,
//       targetCharId 是 0..11 的部位索引 (sub_65816C)。
// 客户端本地从 CUserInventory 拼同一份清单的代码: sub_6A7396 (0x006A7396, count1/count2)
// 与 sub_6A72C2 (0x006A72C2, count3), 字段取的就是物品对象 +48/+60/+72
// = ItemDef 的 skinId / targetCharId / param (见 sub_65A034 0x0065A034)。
//
// ---------------------------------------------------------------------------
// 尾部 E4 字段 f32 = 水泡皮肤编号 (2026-11-10 反编译定案, 与三段清单完全独立)
// ---------------------------------------------------------------------------
// sub_44A856 (0x0044A856) 读到 count2 之后依次是:
//   *(this+29) = ReadUInt8  → f29   (恒 255)
//   *(this+30) = ReadUInt8  → f30   (恒 0)
//   *(this+32) = ReadUInt32 → f32   ← 本字段, 旧实现误当成"占位 0"
//   *(this+19) = ReadUInt8  → count3
// sub_687B3D (0x00687B3D) / sub_4F6E89 (0x004F6E89) 把 a3[32] 交给
// sub_6874A2 (0x006874A2) 写入 CFxContext + 484*slot + 472;
// sub_687DFD (0x00687DFD) 从同一位置读出。全 .text 里这个槽只有这两个
// SetPlayer 类包处理函数会写, 客户端本地穿脱装备不会写它。
//
// 语义铁证: 0x00643xxx 的玩家装扮列表用
//   sub_6777BC(CUserItemMan, 14, 0, 0, sub_687DFD(CFxContext, slot))
// 按 (category=14 水泡, skinId=0, targetCharId=0, param=f32) 反查物品定义
// (sub_6777BC 的四个匹配字段见 0x006777BC)。item_def_base.bin 里
//   930000655 = category 14 / name "BombDeco/Bomb655" / param 655
// 即 f32 就是水泡序号。
// 局内落地: 0x00535E60 附近创建 bomber 时 bomber[288] = sub_687DFD(...);
// 水泡精灵贴图里 this+100 > 0 才走 "BombDeco/" + "Bomb%03d" (sub_45A8EC),
// 否则回落 "Bomb/Bomb" 默认贴图。
// ⇒ 旧实现恒发 0, 进房/进战斗时每次 op 100 都把水泡皮肤覆写成默认,
//   而角色装扮走三段清单照常生效 —— 这就是"装备显示正常、水泡还是默认"的根因。
//
// ⚠️ category 14 不要塞进 count2: 客户端 sub_687B3D 会把每条 count2 写入
//   CFxContext 特效槽, 局内建 bomber 时又按 category 12 特效去应用, 反而会给
//   角色挂一个查不到定义的无效特效。水泡只认 f32 这一个字段。
// ============================================================================

val RAW3_ROOM = byteArrayOf(0xa0.toByte(), 0x6f.toByte(), 0x5a.toByte())
val RAW3_START = byteArrayOf(0xa0.toByte(), 0x6d.toByte(), 0x5a.toByte())

/** CSlotAccessoryPacket 的三段装扮清单 (按客户端解码顺序) */
data class SlotAccessories(
    /** count1: [E1(idx) + E2(value)]，category 10 背景/旗帜/边框 */
    val decorations: List<Pair<Int, Int>> = emptyList(),
    /** count2: [E4 × 3]，category 12 角色特效 */
    val effects: List<Triple<Int, Int, Int>> = emptyList(),
    /** count3: [E4 × 3]，category 19 纸娃娃装扮 */
    val avatars: List<Triple<Int, Int, Int>> = emptyList(),
    /**
     * 尾部 E4 (f32): 当前装备的水泡皮肤编号 = ItemDef.param。
     * 0 = 未装备, 客户端回落默认水泡 "Bomb/Bomb"。
     */
    val bombSkin: Int = 0,
) {
    val isEmpty: Boolean get() =
        decorations.isEmpty() && effects.isEmpty() && avatars.isEmpty() && bombSkin == 0
}

/**
 * 把服务端记录的装备槽 (C→S op 273 的复合槽位 → itemId) 转成装扮清单。
 *
 * 分类以 op 60 ItemDef 定义表为准 —— 客户端 `sub_65D55F` 也是自己按 category 反推
 * 装备大类, 上报的槽位不参与显示计算。按复合槽位排序保证同一份 equips 生成同样的包。
 *
 * category 14 (水泡) 走 f32 而不是 count1/2/3, 见文件头注释。
 */
fun slotAccessoriesOf(
    equips: Map<Int, Int>,
    findItem: (Int) -> ItemDefEntry? = ItemDef::find,
): SlotAccessories {
    val decorations = ArrayList<Pair<Int, Int>>()
    val effects = ArrayList<Triple<Int, Int, Int>>()
    val avatars = ArrayList<Triple<Int, Int, Int>>()
    var bombSkin = 0

    for ((compositeSlot, itemId) in equips.entries.sortedBy { it.key }) {
        if (itemId == 0 || compositeSlot == EquipSlot.BAG) continue
        val def = findItem(itemId) ?: continue
        when (def.category) {
            // 背景/旗帜/边框: idx 就是 targetCharId (0..8), value = skinId (背景序号)
            10 -> if (def.targetCharId in 0..8 && def.skinId in 0..0xFFFF) {
                decorations += def.targetCharId to def.skinId
            }
            // 角色特效: 与 sub_687B3D 的落库校验保持一致
            12 -> if ((def.skinId + 2).toUInt() <= 9u &&
                def.targetCharId.toUInt() <= 8u && def.param >= 0
            ) {
                effects += Triple(def.skinId, def.targetCharId, def.param)
            }
            // 水泡皮肤: f32 = param (水泡序号, 如 Bomb 655 → 655)。
            // 客户端 sub_687B3D 只在 >0 时写入 CFxContext+472, <=0 一律按默认水泡。
            // 同一玩家理论上只装备一件, 按复合槽位排序取第一件, 保证结果确定。
            14 -> if (bombSkin == 0 && def.param > 0) {
                bombSkin = def.param
            }
            // 纸娃娃: param>0 才会被 CGOAvatar 接受, targetCharId 是 12 个部位之一
            19 -> if (def.targetCharId in 0..11 && def.param > 0) {
                avatars += Triple(def.skinId, def.targetCharId, def.param)
            }
        }
    }
    return SlotAccessories(decorations, effects, avatars, bombSkin)
}

fun SetPlayer(
    slotId: Int = 0,
    slot: Slot? = null,
    user: User? = null,
    level: Int = user?.level ?: 5,
    userId: Int = user?.id ?: CAPTURE_USER_ID,
    session: ByteArray = SESSION_STRING,
    isStart: Boolean = false,
    raw3: ByteArray = if (isStart) RAW3_START else RAW3_ROOM,
    equips: Map<Int, Int> = user?.equips.orEmpty(),
) = PKT {
    val acc = slotAccessoriesOf(equips)
    if (!acc.isEmpty) {
        println(
            "[SetPlayer] 槽 $slotId 装扮: 背景/边框=${acc.decorations.joinToString { "(${it.first},${it.second})" }} " +
                "特效=${acc.effects.size} 纸娃娃=${acc.avatars.size} 水泡皮肤=${acc.bombSkin}"
        )
    }

    // 1. CSetPlayerPacket
    Encode1(slotId)
    Encode4(userId)
    if (isStart) {
        Encode1(0) // flag16 = 0 (无 enterEffectId, 77B)
    } else {
        Encode1(1) // flag16 = 1 (带 enterEffectId, 78B)
    }
    Encode1(0)     // flag20
    Encode1(0)     // flag36
    Encode1(0)     // dummy
    if (!isStart) {
        Encode1(255) // enterEffectId
    }
    EncodeBuffer(raw3)

    // 2. CUser::Decode
    Encode2(session.size)
    EncodeBuffer(session)
    Encode1(0)     // user_v1
    Encode1(0)     // user_v2
    Encode4(0)     // user_v3
    Encode1(0)     // user_v4
    Encode4(0)     // user_v5
    Encode2(level) // 等级 level (核心字段: 计算槽位外观与段位图标)
    Encode4(0)     // user_v7
    Encode4(0)     // user_v8
    Encode4(0)     // user_v9
    Encode4(0)     // user_v10
    Encode1(0)     // user_v11
    Encode4(0)     // user_v12
    Encode2(0)     // user_s1
    Encode2(0)     // user_s2
    Encode2(0)     // user_s3
    Encode2(0)     // user_s4

    // 3. Tail of CSetPlayerPacket
    Encode4(0)     // tail_u32
    Encode1(8)     // tail_f40
    Encode1(0)     // tail_f44

    // 4. CSlotAccessoryPacket —— 顺序必须与 sub_44A856 的读取顺序严格一致
    Encode2(0)     // acc_u16 (客户端读出后丢弃)
    Encode1(acc.decorations.size)
    acc.decorations.forEach { (idx, value) ->
        Encode1(idx)
        Encode2(value)
    }
    Encode1(acc.effects.size)
    acc.effects.forEach { (a, b, c) ->
        Encode4(a)
        Encode4(b)
        Encode4(c)
    }
    Encode1(255)   // acc_f29
    Encode1(0)     // acc_f30
    // f32 = 水泡皮肤编号 (CFxContext+472 → bomber 水泡贴图 "BombDeco/Bomb%03d")。
    // ⚠️ 恒 0 会让客户端每收到一次 op 100 就把水泡覆写成默认, 见文件头注释。
    Encode4(acc.bombSkin)
    Encode1(acc.avatars.size)
    acc.avatars.forEach { (a, b, c) ->
        Encode4(a)
        Encode4(b)
        Encode4(c)
    }
}
