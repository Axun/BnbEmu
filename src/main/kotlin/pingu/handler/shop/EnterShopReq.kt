package pingu.handler.shop

import pingu.netty.Decode1
import pingu.netty.PKTHandler
import pingu.packet.EnterShopResult
import pingu.packet.EnterShopStage

/**
 * C->S op 315: 请求进入商城 (EnterShopReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 客户端在准备/大厅界面点击商城按钮时于 0x004FBDCD 调用 CShopConnectionMan::RequestEnterShop (0x005BE538)
 * - 发送载荷:
 *   - Encode1(param): tab 或 category (点击商城默认发 0, 混淆字节 5A)
 *   - 同时客户端设置等待锁 [CShopConnectionMan + 0x10] = 2
 * - 服务端响应:
 *   1. 下发 S->C op 259 EnterShopResult(success = true) 解除等待锁
 *   2. 下发 S->C op 17 EnterShopStage() 触发客户端切换至 CShopStageNew (Stage 14)
 */
val EnterShopReq = PKTHandler { c ->
    val tab = Decode1
    c send EnterShopResult(success = true)
    c send EnterShopStage()
}

/*
  大厅点击商城按钮时，客户端调用 `0x004FBDCD` -> `CShopConnectionMan::RequestEnterShop` (`0x005BE538`)，
  将 `[CShopConnectionMan + 0x10] = 2` 设置等待锁，并向服务端发出 **C→S op 315**（`Encode1(tabIdx)`，1 字节，实测 `5A` 对应 tab 0）。
  服务端必须依序下发两包响应：
  1. **S→C op 259 EnterShopResult**（`Encode1(if (success) 1 else 0)`）：由 `CShopConnectionMan::OnPacket` (`0x005BE76B`) 处理，
     校验通过后将等待锁 `[esi + 0x10] = 0` 清除，并触发客户端向服务端同步请求：**C→S op 37 ShopUserSyncReq**、**C→S op 317 ShopSyncReq** 及 **C→S op 97 UserStageStateReq** (`Encode1(1)`)。
  2. **S→C op 17 EnterShopStage**（⚠️ 原 YAML 误配为 op 18，实测与逆向 `0x00502B72` 确认真实为 op 17，32 字节）：
     包含 `E1(mode) + E1(subMode) + E4(f3) + E4(f4) + E4(f5) + E4(catalogRevision) + E4(rev2) + E4(rev3) + EStr(msg) + E4(bonusCount)`。
     客户端 `0x004FB0BE` 收到后调用 `CMainSystem::Instance().SetStage(14)` (`0x0068DC53`) 切换至 `CShopStageNew`（商城舞台），并将 `[CShopConnectionMan + 0x10] = 1`（置 `IsInShop() = true`）。
  3. **商城本地缓存校验与请求（C→S op 276 / 258 / 278）与商城空白修复**：
     客户端 `CShopItemMan` 依次校验本地 3 份配置缓存：
     - `0x00670CBE` 校验 `fxstock.ini`（catalogRevision）：
       客户端调用 `0x0074EE50` 计算本地文件的 Adler-32 哈希（以 0 为初始值计算数据体 `adler32Zero`）。
       ⚠️ **商城页面全部空白的根本原因**：此前服务端各处硬编码 `catalogVersion = 0x75D92163`（此为原版 573 项基础数据块的哈希）。因服务端注入了蓝妹妹道具卡（574 项），实际数据块哈希变为 `0x361998E1`。客户端比对 `0x361998E1 != 0x75D92163` 后，在 `0x00670D2A` 将载入标志 `[esi + 0x28]`（商品库存）、`[esi + 0x2c]`（字串表）、`[esi + 0x30]`（套餐图）全部清零并标记失效，同时向服务端发送 **C→S op 276** 重新请求目录！因旧版服务端未应答 op 276，导致客户端 `CShopItemMan` 库存永久处于未加载状态，商城各分类页面均无法显示任何商品。
     - 修复方案：
       1. `ShopCatalog.CATALOG_HASH` 基于当前注入后的数据块动态计算其实际 `adler32Zero`（`0x361998E1`），`EnterShopStage`、`ShopVersion` 与 `MyPageInfo` 均统一引用此动态版本哈希，实现客户端本地版本一致命中；
       2. 若客户端本地缓存缺失或不一致主动发送 **C→S op 276** 时，服务端在 `Unk_276` 中回送 **S→C op 65 `ShopCatalog()`**；
       3. `0x00670D66` 校验 `packagemap.ini`（rev3）缺失发送 **C→S op 258** 时回应 **S→C op 66 `Unk66()`**；
       4. `0x00670E05` 校验 `stringtable.ini`（rev2）缺失发送 **C→S op 278** 时回应 **S→C op 204 `Unk204()`**。
     紧接着客户端发送 **C→S op 297** 登记 4B 会话令牌完成就绪同步。
  实现见 `handler/shop/EnterShopReq.kt` + `packet/EnterShopResult.kt` + `packet/EnterShopStage.kt` + `handler/Unk_276.kt` + `handler/shop/ShopSyncReq.kt` + `handler/shop/ShopUserSyncReq.kt` + `handler/shop/ShopPackageMapReq.kt` + `handler/shop/ShopStringTableReq.kt`.
- **退出商城流程与防止误弹账号信息对话框（2026-11-02 逆向 CA.exe 确认）**：
  1. **发包时序（`GlobalUI/BtBack` 0x1771）**：
     在商城界面点击返回/退出按钮时，调用 `CGlobalButtonMenuDlg::OnButtonClick` (`0x00490368` Case 1 `0x1771`) -> `0x0048FC3B` -> `0x0048FE33` (`vtable[80]` 即 `CShopStageNew::ExitShop` `0x005CDA35`)。
     客户端依序发出：
     - **C→S op 273 `ChangeEquipReq`**：`Encode1(1) + Encode1(slot) + Encode2(count) + ...`，同步选角换装（11 字节）；
     - **C→S op 73 `UserInfoReq`**：调用 `0x004F881E` 发送 `Encode1(count) + repeat(count) { Encode1(bomberId) }`（实测 `5B 5A`），上报当前选中的角色；
     - **C→S op 97 `SelectMapReq`**：调用 `0x004F73B2(0)` 发送 `Encode1(0)`（实测 `5A`），请求退出商城舞台。
  2. **无法退出与误弹个人名片（My Page）对话框的双重根因**：
     - **误弹名片根因**：`CA.exe` 中 `CMyPageDialog` 唯一定义的构造与弹出点位于 `0x004FBB6F` (`CStage::OnUserInfoResult` -> `0x004FBFD4`)。当客户端收到 **S→C op 73 `UserInfoResult`**（且 `isSelf == 1`、`errCode == 0`）时，会无条件创建并弹出 `CMyPageDialog`！此前服务端在收到 C→S op 73 时错误地调用了 `sendUserInfo` 下发 S→C op 73，导致客户端在退出商城时被动唤醒个人资料弹窗。实际上，C→S op 73 仅用于向服务端上报当前选中的 bomber 角色，绝不应回显 S→C op 73（打开名片由 C→S op 66 `UserRestrictReq` 的 `isMe == 1` 分支精准触发）。
     - **无法退出根因**：客户端发送 C→S op 97 (`stageState = 0`) 后等待服务端指示目标阶段。服务端此前未处理 `stageState == 0`，未下发 **S→C op 97 `ChangeStage(11)`**。
  3. **修复方案**：
     - `UserInfoReq.kt` 中仅根据载荷更新玩家槽位的 `bomber` 角色，移除 `sendUserInfo` 调用，杜绝非 MyPage 场景下的误弹窗；
     - `SelectMapReq.kt` 中当 `stageState == 0` 时，向客户端下发 **S→C op 97 `ChangeStage(11)`**。客户端 `CShopStageNew::OnPacket` (`0x005CDD15` -> `0x005CDDB8`) 收到后调用 `CMainSystem::SetStage(11)` 销毁商城舞台并切回大厅，同时自动向服务端发送 **C→S op 78 `StageInitReq`**，平滑进入大厅。
  实现见 `handler/room/SelectMapReq.kt` + `handler/UserInfoReq.kt`，单元测试见 `src/test/kotlin/pingu/netty/ShopTest.kt`。
 */