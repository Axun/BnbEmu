package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.packet.CAPTURE_USER_ID
import bnbemu.packet.SESSION_STRING
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
//   4. CSlotAccessoryPacket (11B):
//      Encode2(acc_u16=0) + Encode1(acc_count1=0) + Encode1(acc_count2=0) +
//      Encode1(acc_f29=255) + Encode1(acc_f30=0) + Encode4(acc_f32=0) + Encode1(acc_count3=0)
// ============================================================================

val RAW3_ROOM = byteArrayOf(0xa0.toByte(), 0x6f.toByte(), 0x5a.toByte())
val RAW3_START = byteArrayOf(0xa0.toByte(), 0x6d.toByte(), 0x5a.toByte())

fun SetPlayer(
    slotId: Int = 0,
    slot: Slot? = null,
    user: User? = null,
    level: Int = user?.level ?: 5,
    userId: Int = user?.id ?: CAPTURE_USER_ID,
    session: ByteArray = SESSION_STRING,
    isStart: Boolean = false,
    raw3: ByteArray = if (isStart) RAW3_START else RAW3_ROOM,
) = PKT {
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

    // 4. CSlotAccessoryPacket
    Encode2(0)     // acc_u16
    Encode1(0)     // acc_count1 (槽位装扮数)
    Encode1(0)     // acc_count2 (角色装扮数)
    Encode1(255)   // acc_f29
    Encode1(0)     // acc_f30
    Encode4(0)     // acc_f32
    Encode1(0)     // acc_count3
}
