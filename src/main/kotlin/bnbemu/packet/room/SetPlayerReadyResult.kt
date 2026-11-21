package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.EncodeSlotMeta
import bnbemu.server.Slot

// ============================================================================
// S→C op 102: 槽位准备/元数据同步 (SetPlayerReadyResult)
// 对应 C→S op 84 SetPlayerReadyReq 的应答。
//
// CA.exe 两侧接收点：
//   - CPrepareStage::OnPacket (0x52A949 case 102 -> sub_524A22):
//       Decode1(slot) + 3B ssdEncode(meta[0..2]) + Decode1(attr=0)
//       写入 slot 3B meta，当 bit0 (FLAG_CLOSED) 未翻转且为本地玩家槽位时
//       将 [this+12152] ([this+3038]) 等待锁 4 清零，并调用 EnableUI(1) 解锁开始/准备按钮。
//   - CSlotStage::OnPacket (0x4F480C case 102 -> sub_4F22C0):
//       Decode1(slot) + 3B ssdEncode(meta[0..2])，同样清零等待锁 4 ([this+3033])。
// ============================================================================
fun SetPlayerReadyResult(
    slotId: Int = 0,
    slot: Slot = Slot(),
    attr: Int = 0,
) = PKT {
    Encode1(slotId)
    EncodeSlotMeta(slot)
    Encode1(attr)
}
