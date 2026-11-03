- **设置电脑敌人等级（op 88 AILevelChangeReq，2026-09-10 逆向 CA.exe 确认）**：在房间/练习房界面调整
  AI 难度等级时由客户端发出 **op 88 (0x58)**（CA.exe 中 `CPrepareStrategyAIBattle::OnButtonClick`
  触发，原 TW_5 为 op 110）。body = `E1(chief/f1) + E1(slotIdx) + E1(newLevel) + E1(flags/f4)`（4 字节）；
  服务器更新 `Room.slots[slotIdx].AILevel = newLevel` 并下发 **op 103 PracticeSlot103**
  （`E2(slotIdx) + E1Bool(isAI) + E2(newLevel) + E2(flags)`，7 字节），`CPrepareStrategyAIBattle::OnPacket`
  (`0x52d188` -> Case 103 `0x52b39a`) 收到后调用 `0x52b048`/`0x52b0ad` 刷新槽位 UI 并执行
  `and dword ptr [esi + 0x3250], 0` 清除等待锁。
- **踢出电脑敌人 / 打开关闭槽位（op 89 OpenCloseSlotReq，2026-09-10 逆向 CA.exe 确认）**：在房间/练习房
  界面踢出 AI 或切换槽位开关时由客户端发出 **op 89 (0x59)**（CA.exe 中 `0x4f6866` / `0x51c7ea` 触发，
  原 TW_5 为 op 100）。body = `E1((slotIdx << 4) | (action & 0x0F))`（1 字节）；
  服务器重置该槽位 AI 状态并下发 **op 106 PracticeSlotReady**（`E1((slotIdx << 4) | state)`，清除
  `CSlotStage` 的 `[esi + 0x2f64]` 等待锁 2）与 **op 103 PracticeSlot103(isAI=false)**（`E2(slotIdx) + E1Bool(0) + E2(0) + E2(0)`，
  触发 `CPrepareStrategyAIBattle` 的 `0x685ed9` 移除 AI 槽位并清除策略等待锁）。
- **房间内切换角色（C→S op 87，2026-10-31 逆向 CA.exe 确认）**：在房间界面点击槽位里的角色头像切换
  bomber 时由客户端发出 **op 87 (0x57)**（CA.exe `0x4F67E9`，`CPrepareStrategyAIBattle` 的槽位角色设置；
  原 TW_5 把切换角色放在 op 99，真实 EN 客户端发的是 87）。body = `E1(slotIdx) + E1(bomberIdx)`（2 字节，
  实测明文 `5A 5C` → 槽位 0 / bomber 6）。
  ⚠️ 发包后客户端立刻把 `[esi + 0x2f64] = 5` 锁住该槽位（`0x4F6848`），锁未清前 `0x4F67F7` 的 guard 会
  直接丢弃后续 op 87 → **服务器必须回 S→C op 105**（`E1(slotIdx) + E1(bomberIdx)`，2 字节）才能解锁。
  房间阶段分派器 `0x4F480C` 的 op 105 分支走虚表 `+0x1A0` → `0x4EFBF6`：只读两个 Encode1 应用角色后，
  于 `0x4EFC96` 把 `[+0x2f64] == 5` 清零。缺此应答 → 槽位永久卡等待锁、再点角色无任何反应。
  实现见 `handler/room/SelectBomberReq.kt` + `packet/room/SlotBomber.kt`（op 105 的 2B 变体，
  与 SetPlayer/PracticeSetPlayer 的整槽版同 op 异体）；回归测试 `tools/test_room_select_bomber.py`。
- **房间内选择地图（C→S op 85 SelectMapReq / S→C op 108 SelectMap，2026-11-03 逆向 CA.exe 确认）**：在房间界面选择地图时由客户端发出 **op 85 (0x55)**（CA.exe 中 `0x51c60f` 触发，原 TW_5 为 op 97）。body = `E2(themeId) + E4(mapId)`（6 字节，实测 `A5 75 96 CA 53 3A` → themeId 28, mapId 175）。
  ⚠️ 发包后客户端在 `0x51c65b` 设置选图等待锁 `[esi + 0x2f78] = 1` 并调用虚表 `[+0x1c4](0)` 禁用选图按钮。
  **服务端响应必须下发 S→C op 108 SelectMap**（原 TW_5 误标为 112；112 实为开局 165B 对局地图与出生点初始化 `GameStartMapInfo`）。
  `CPrepareStage::OnPacket` (`0x52a949` -> Case 108: `0x51efe0`) 收到后：
  1. 检查 `[esi + 0x2f78] == 1` 并清零解锁，调用 `[edx + 0x1c4](1)` 重新启用选图按钮；
  2. 读取 `E2(result)`，若为 0 进入成功分支 `0x51f105`；
  3. 读取 `E2(themeId)` 并应用主题，再读取 `E4(mapId)` 并调用 `0x51d9fb` 刷新房间界面地图 UI。
  若错发 op 112，`CPrepareStage` 会将其送入 `0x51f29c`，因 payload 不足 165B 抛异常导致客户端销毁当前舞台并发送 `op 78 StageInitReq` 异常退回大厅。
  实现见 `handler/room/SelectMapReq.kt` + `packet/room/SelectMap.kt`；单元测试见 `SelectMapTest.kt`。
