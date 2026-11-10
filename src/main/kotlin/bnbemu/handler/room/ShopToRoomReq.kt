package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ClientStage
import bnbemu.packet.isRoomStage
import bnbemu.server.sendRoomEntryChain

/**
 * C→S op 80: 客户端「切回房间舞台」时的会话回报 (ShopToRoomReq)
 *
 * 切回房间舞台时重新登记会话：body = `E2(sub_687ECF(CGameContext))`
 * + `E4(sub_686254(CGameContext))`（会话/频道信息）。
 * 客户端不等回应，所以这里只消费载荷、不回 S→C op 80。
 *
 * ## 不是「商城专用」，但触发条件唯一
 * 2026-11-10 用 `reference/原版客户端/CA.exe` 原始二进制复核（pefile + capstone 遍历整个
 * `.text` 的 E8 调用与 `6A 50` 字节，不依赖 IDA 导出的 `CA.exe.c`）：
 *
 * - 全 .text 共 550 个 CSendPacket 构造调用点（CtorWithOp 415 / BeginOp 41 / 其它包装 94），
 *   其中 **`push 0x50` + `call CSendPacket_CtorWithOp(0x0064EE1C)` 只有 2 处**：
 *     · `0x005CDD3D`（`sub_5CDD15` +0x28；其调用者 `sub_5D5CBA` 在虚表 `0x008AF174` 的 op 97 case）
 *     · `0x0062F750`（`sub_62F728` +0x28；其调用者 `sub_631C78` 在虚表 `0x008B441C` 的 op 97 case）
 *   两份处理器代码逐字节相同；两张虚表槽位一一对应、多数指向同一批通用函数，是同一舞台基类的
 *   **两套平行商城舞台实现**，不是「一个商城 + 一个别的功能」。
 *   把前置窗口放宽到 320B 复核 `mov reg,0x50 → push reg` 形式，仍只有这两处。
 * - `sub_4F748F`（stageId ∈ {12,16,17,18} 判定）在二进制里共 4 个调用者：上述 2 个发 op 80，
 *   另 2 个（`sub_5D1106` / `sub_6300BD`）是这两套商城舞台的构造/初始化，不发 op 80。
 * - 因此 op 80 的**唯一**发送条件是：OnPacket 收到 S→C op 97，且 `E2(stageId)` 被判定为房间舞台。
 *   发完立即 `sub_68DC53(CMainSystem, stageId)` 切舞台，**没有等待锁**。
 *   ⇒ 任何「用 ChangeStage 切回房间」都会发；商城退出只是我们服务端目前会触发的那一条路径。
 *
 * 这里把服务端舞台状态同步成房间：万一别的分支改动过 `c.stage`，以客户端实际到达的舞台为准，
 * 保证下一次「房间内进商城」仍能正确返回房间。
 */
val ShopToRoomReq = PKTHandler { c ->
    val sessionShort = if (readableBytes() >= 2) Decode2 else 0
    val sessionId = if (readableBytes() >= 4) Decode4 else 0
    c.stage = if (isRoomStage(c.shopReturnStage)) c.shopReturnStage else ClientStage.Room.value
    println("[ShopToRoomReq] op80 客户端已切回房间舞台 stage=${c.stage} | sessionShort=$sessionShort sessionId=$sessionId")
    sendRoomEntryChain(c);
}
