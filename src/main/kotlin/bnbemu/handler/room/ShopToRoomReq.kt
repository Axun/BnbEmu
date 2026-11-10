package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.ClientStage
import bnbemu.packet.ItemDef
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.UserDynamicInfoValue
import bnbemu.packet.UserItemUpdate
import bnbemu.packet.bomberCharCard
import bnbemu.packet.isRoomStage
import bnbemu.server.lucci
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
 *
 * ## 角色回灌（修复「进商城返回后角色变宝宝」）
 * C→S op 73 上报的是客户端 `CUserDynamicInfo` 单例里的角色字段
 * (CA.exe `sub_4F881E` → `sub_6E50F0` 读 CUDI + 12*slot + 444)，而 [sendRoomEntryChain]
 * 只重设房间槽位 (CFxContext / 槽位 UI)，**从不写 CUserDynamicInfo**。两份角色存储互不相干，
 * 抓包里 op 105 的角色值 5 只是 AI 槽默认值，不能据此推断客户端角色被改。
 *
 * 从商城返回时客户端仍带着商城舞台留下的角色值；房间链不会纠正它，于是打开名片时 op 73
 * 把旧值上报回来，`UserInfoReq` 写进槽位，`sendUserInfo` 又优先读槽位 → 名片显示该值。
 * 首次进房不出问题，是因为进房前刚走过进频道握手 (op 81 → `JoinSessionReq`)，那批包里的
 * op 20 已把 CUDI 写成正确角色；op 80 路径没有这一步。
 *
 * ⇒ 只需在重放房间链之后按 `user.bomber` 补发 op 20，把权威角色写回客户端 CUDI；
 *   之后客户端上报、服务端同步、名片生成自然一致，无需进商城前快照、忽略上报或重设槽位。
 *   op 20 会把 Lucci 字段刷成 0，末尾紧跟 op 122 补回当前金币。
 */
val ShopToRoomReq = PKTHandler { c ->
    val sessionShort = if (readableBytes() >= 2) Decode2 else 0
    val sessionId = if (readableBytes() >= 4) Decode4 else 0

    val user = c.users.firstOrNull()
    val bomber = user?.bomber ?: 0

    c.stage = if (isRoomStage(c.shopReturnStage)) c.shopReturnStage else ClientStage.Room.value
    println("[ShopToRoomReq] op80 客户端已切回房间舞台 stage=${c.stage} | sessionShort=$sessionShort sessionId=$sessionId")
    sendRoomEntryChain(c)

    // 按服务端权威角色回灌客户端 CUserDynamicInfo 的角色字段 (op 20 第 12 项)，
    // 它直接决定下一次 C→S op 73 的上报值；op 122 补回被 op 20 刷成 0 的 Lucci。
    c.send(
        UserDynamicInfo(bomber, user?.level ?: 5),
        UserDynamicInfoValue(0, c.lucci),
    )
}
