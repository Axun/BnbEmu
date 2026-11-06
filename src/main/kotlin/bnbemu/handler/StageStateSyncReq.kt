package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChangeStage

/**
 * C→S op 97: 舞台状态同步 / 退出当前舞台请求 (StageStateSyncReq)
 *
 * 逆向确认 (CA.exe EN v18, 2026-11-02):
 * - 发包点只有 `CStage::OnStageStateSync` (`0x004F73B2`)，body = `Encode1(stageState)` 共 1 字节。
 * - 两处调用者：
 *   1. **进入商城**：`CShopConnectionMan::OnPacket` (`0x005BE76B`) 收到 S→C op 259 EnterShopResult
 *      成功后调用 `0x004F73B2(1)`，发包 `5B`（E1(1)），仅上报「我已在商城舞台」，客户端不需要任何回应。
 *   2. **退出商城**：`CShopStageNew::ExitShop` (`0x005CDA35`) 经 `0x0048FE33` 调用 `0x004F73B2(0)`，
 *      发包 `5A`（E1(0)），随后 `CShopStageNew::OnPacket` (`0x005CDD15` → `0x005CDDB8`)
 *      **等待服务端下发 S→C op 97 ChangeStage(11)** 才调用 `CMainSystem::SetStage(11)` 销毁商城舞台
 *      切回大厅，并自动补发 C→S op 78 StageInitReq（由 `handler/StageInitReq.kt` 回整套大厅数据）。
 *
 * ⚠️ 历史故障：EN 版的「房间内选图」已被重编号到 op 85（见 `handler/room/SelectMapReq.kt`），
 *    但退出商城的舞台状态包仍是 op 97（TW_5/CN_12/JP_17 里 op 97 恰好就是 SelectMapReq，
 *    所以旧代码把退出逻辑塞在 SelectMapReq 的 1 字节分支里）。EN 表的 SelectMapReq 绑在 85 上，
 *    op 97 没有任何 binding → 日志 "[UNKNOWN] 97 | 0x61 | 找不到 Handler"，
 *    客户端永远等不到 ChangeStage(11)，表现为点「退出」毫无反应、卡死在商城舞台。
 */
val StageStateSyncReq = PKTHandler { c ->
    if (readableBytes() < 1) return@PKTHandler

    when (val stageState = Decode1) {
        0 -> {
            // 请求离开当前舞台（商城 ExitShop）：回 ChangeStage(11) 切回大厅
            println("StageStateSyncReq -> stageState=0 (退出商城舞台), 回 ChangeStage(11)")
            c send ChangeStage(11)
        }
        else -> {
            // 1 = 已进入商城舞台的上报，客户端不等待回应
            println("StageStateSyncReq -> stageState=$stageState (仅上报, 无回应)")
        }
    }
}
