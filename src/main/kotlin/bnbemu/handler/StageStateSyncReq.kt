package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChangeStage
import bnbemu.packet.ClientStage
import bnbemu.packet.isRoomStage

/**
 * C→S op 97: 舞台状态同步 / 退出当前舞台请求 (StageStateSyncReq)
 *
 * 逆向确认 (CA.exe EN v18, 2026-11-02):
 * - 发包点只有 `CStage::OnStageStateSync` (`0x004F73B2`)，body = `Encode1(stageState)` 共 1 字节。
 * - 两处调用者：
 *   1. **进入商城**：`CShopConnectionMan::OnPacket` (`0x005BE76B`) 收到 S→C op 259 EnterShopResult
 *      成功后调用 `0x004F73B2(1)`，发包 `5B`（E1(1)），仅上报「我已在商城舞台」，客户端不需要任何回应。
 *   2. **退出商城**：`CShopStageNew::ExitShop` (`0x005CDA35`) 经 `0x0048FE33` 调用 `0x004F73B2(0)`，
 *      发包 `5A`（E1(0)），随后商城舞台 OnPacket (`sub_5D5CBA`，虚表 `0x008AF174`) 的 op 97 case
 *      (`0x005D5D33`) → `sub_5CDD15` 读 `E2(stageId)`；另一套平行商城舞台
 *      (`sub_631C78`，虚表 `0x008B441C`) 的 `sub_62F728` 是逐字节相同的实现。
 *      它**等待服务端下发 S→C op 97 ChangeStage(stageId)**，读 `E2(stageId)` 后分派：
 *        · `sub_4F748F(stageId)` 为真（12/16/17/18，房间舞台）→ 发 C→S op 80 后 `SetStage(stageId)` **回房间**；
 *        · 否则特殊模式 `SetStage(20)` 广场，普通模式 `SetStage(11)` 大厅 + 自动补发 C→S op 78 StageInitReq。
 *      即「回大厅还是回房间」完全由服务端这个 stageId 决定，客户端自己不记来源。
 *      ⚠️ 因此不能用固定值 11：从大厅进商城回 ChangeStage(11)，从房间进商城必须回
 *         ChangeStage(12)（来源见 [bnbemu.netty.ClientSocket.shopReturnStage]，由 EnterShopReq 记录）。
 *      S→C op 155 BackToRoom 只挂在 stage 13 对局分发器上，商城舞台没有该分支，不能替代 op 97。
 *
 */
val StageStateSyncReq = PKTHandler { c ->
    if (readableBytes() < 1) return@PKTHandler

    when (val stageState = Decode1) {
        0 -> {
            // 请求离开当前舞台（商城 ExitShop）: 按进商城前的舞台决定去向。
            // 房间舞台(12/16/17/18) → ChangeStage(房间), 客户端发 op 80 并 SetStage 回房;
            // 其他(默认大厅 11)       → ChangeStage(11), 客户端自动补发 op 78 回大厅。
            val target = if (isRoomStage(c.shopReturnStage)) c.shopReturnStage else ClientStage.Lobby.value
            c.stage = target
            println(
                "StageStateSyncReq -> stageState=0 (退出商城舞台), 来源=${c.shopReturnStage}, " +
                    "回 ChangeStage($target) [${if (target == ClientStage.Lobby.value) "大厅" else "房间"}]"
            )
            c send ChangeStage(target)
        }
        1 -> {
            // 已进入商城舞台的上报，客户端不等待回应
            c.stage = ClientStage.Shop.value
            println("StageStateSyncReq -> stageState=1 (仅上报, 无回应)")
        }
        else -> {
            println("StageStateSyncReq -> stageState=$stageState (未知状态, 仅上报, 无回应)")
        }
    }
}
