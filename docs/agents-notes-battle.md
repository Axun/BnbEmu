- **对局阶段启动与水泡引爆机制**：
    1. **练习房/单人模式（tst.pcap stream 4）**：
       由 `Unk_92` (C→S op 92) 触发开始序列：`[111 AILevelChange, 112 GameStartMapInfo, 100 PracticeStartInfo, 136 PracticeBoard]`。
       - **S→C op 112 GameStartMapInfo（对局地图与出生点/角色槽位初始化，165 字节，CA.exe 0x51F29C）**：在开局时初始化随机数种子、主题 ID、加密地图名（`EncodeCipherStr` 经 `0x75daf0` 加密为 10B `"Cemetery04"`）、地图 ID、8 个槽位的出生点/角色/队伍标志等。若地图校验失败会弹出 `"InvalidMapReturnToLobby"` 退回大厅。实现见 `packet/room/GameStartMapInfo.kt`。
       `PracticeBoard (op 136)` (`0x00556b1d` -> `0x00553aa0`) 会直接调用 `0x0053291c` 启动对局全局时钟 `CGameClock` 并初始化所有 Bomber 槽位（设置 `m_bActive = 1`）。
       ⚠️ **练习房绝不能发送 op 170 / op 171**（op 171 的 `0x00558667` 会设置 `[esi + 0x311c] = 1` 开启多人倒计时 UI；若传入 0 秒时限会导致界面顶部倒计时初始即为 0 且超时卡死）。
    2. **局内炸弹状态与引爆机制（S→C op 138 SetBombState / S→C op 141 DetonateBomb / S→C op 148 TileBombExplode）**：
        - **S→C op 138 SetBombState**（`0x55A627` -> `0x5481BD`）：`Encode1(count) + N * [Encode2(bombId) + Encode1(state) + if (state == 0) Encode4(param)]`。生成炸弹时下发 state=1 激活状态；`0x534B18` 收到 state=0 时调用 `0x565E9F` 引爆炸弹。
        - **S→C op 141 DetonateBomb / BombIgnite**（`0x55A600` -> `0x54BD14`）：`Encode1((action << 4) | (slotId & 0x0F)) + Encode2(bombId) + Encode1(x) + Encode1(y) + Encode1(isSpecial) + Encode1(flags) + Encode1(power)`。action=0 为放置水泡，action=4 为引爆炸弹。
        - ⚠️ **S→C op 148 实为 DropObstacleResult（放置地面障碍物，绝非 TileBombExplode！）**（`0x55A69B` -> `0x54BA1A` -> `0x477CEF`）：`Encode1((hasItem<<6) | (obstacleType&0x0F)) + Encode1(x) + Encode1(y)`（3 字节）。`0x477CEF` 根据低 4 位 `obstacleType` 在地图格生成地面陷阱：`1`=地雷、`2`=陷阱、`3`=`Item/BananaGround`（香蕉皮）、`4`=`Item/PushpinGround`（图钉/钉子）。此前误把水泡 `ownerSlotId` 传给 op 148，导致 AI 槽位 3/4 每次水泡爆炸都在原地凭空生成香蕉皮或钉子！水泡正常到期引爆**只需下发 S→C op 138 SetBombState(bombId, state=0)**（`0x534B18 -> 0x565E9F` 已完整执行十字水柱、炸毁箱子与爆出道具）。
    3. **客户端事件与状态同步上报机制（C→S op 149 EventItemReq，2026-09-30 逆向 CA.exe 确认）**：
        - **开局就绪通知（Ready Ack）**：当客户端收到 `PracticeBoard (op 136)` 完成本地棋盘与槽位初始化后，于 `CA.exe 0x00553E5F` 自动发送 **C→S op 149**（`slotId = -1 (0xFF)`, `token = userId (7215)`, `action = 0`, `param = -1`，混淆字节 `A5 96 CA 4F BA 5A 69 35 AC 6A`）。此包为对局加载就绪同步包，**绝非放置炸弹**。
        - **局内异常回退与状态上报**：客户端在 `0x0044BD9B -> 0x0044BE31` (收到 op 139 时未找到匹配待确认水泡)、`0x0054A58A` (Action 1: 炸弹计数不一致)、`0x0054A678` (Action 2: 放置炸弹失败回退)、`0x0054BFF7` (Action 3: 碰撞校验不一致)、`0x0054A6E7` (Action 4: 引爆炸弹失败回退)、`0x00549DDF` (Action 10: 特殊道具/事件) 等处发送 **C→S op 149**，作为状态同步/异常回退通知。
        - **局内操作包（C→S op 112）**：局内本地玩家移动、使用道具（Action 0x20）与踢水泡（Action 0x40）等在 `0x0054C8B3` 构造并发送 **C→S op 112**。
- **对局期周期性 S→C op 172 = MapEventSync（地图事件/波时间轴同步），不是 ItemThrow**：
  真实服务器对局中每 ~400ms 发一次，body = `E2(sessionA) E2(sessionB) E1(eventId) E4(timeMs) E1(count) + count×[E1 a E2 b E1 c E1 d]`
  （抓包 13 样本：sessionA=176、sessionB=29、eventId=1、timeMs=401/801/…/5203、count=1、条目 `(0,0,0,秒计数)`）。
  客户端链路：局内分发器 `0x0055A76D` 表项 0 → `0x0055A7B0` → 处理器 `0x00559FA1`
  （先校验 sessionA == `[session->0x30CC]->+0x3E080`、sessionB == `0x00687C57` 返回值，不符整包丢弃）
  → `CGameContext::OnEvent 0x00533DBE`（按 `[event+0x104]` 匹配事件链表 `+0x3D558`）
  → `0x0047299C` 播放 `"Wave/"` + 波文件名（飞机事件默认 `"Game/AirPlane1"`）。
  **结论：与道具抛掷、与 AI 行动都无关**；客户端 AI 完全本地运行（`.?AVCAIPlayer@@` / `.?AVCAIGoal*@@`），
  服务端不需要任何周期广播。包定义见 `packet/room/MapEventSync.kt`，复现脚本 `tools/rev_op172.py`。
  另注：**C→S op 172 是另一回事**（`BomberActionAckReq`，2B 空确认），勿混淆。
- **局内放置水泡与引爆完整闭环（C→S op 106 SetBomberEventReq / S→C op 139 SetBomberEvent）**：
    1. **客户端放置水泡请求（C→S op 106）**：玩家或 AI 放泡时（`0x006375A1` / `0x00534CCA`），先由 `CBombBag::0x0044C056` 在本地水泡池 `[esi+0x3c]` 分配待确认水泡（`+0xc0=1, +0x90=-1`），通过 `0x0044BFE0` 生成全局唯一 `bombToken = slotId + 8*k`（存入 `bomb+0x13c`，第 1 轮恰等于 `slotId`，第 2 轮为 `slotId+8`），再经 `CGameMsgCache::0x005391AB -> 0x00539A4B` 发送 **C→S op 106**（11B body = `E2(subType=0) E1(slotId) E4(bombToken) E1(gridX) E1(gridY) E1(flag0) E1(flag1)`）。
    2. **服务端确认与倒计时注册（S→C op 139）**：服务端在 `SetBomberEventReq.kt` 收到 op 106 后，必须：
        - 调用 `Room.addBomb(slotId, gridX, gridY, bombId = bombToken)` 将水泡加入 `Room.bombs` 并启动 2.5s 倒计时；
        - 回显 **S→C op 139 SetBomberEvent**（24B body = `E2(subType) E1(slotId) E4(bombToken) E4(0) E4(0) E1(gridX) E1(gridY) E1(flag0) E1(flag1) E2(bombId) E2(0) E1(0)`）。客户端 `0x0054A3EA -> 0x00534C3D -> 0x0044BD9B` 用第 3 栏 `bombToken` 匹配待确认水泡（`0x0044BE05`），匹配成功后调用 `0x0045976E` 将第 10 栏 `bombId` 写入 `bomb->m_nBombId (+0x90)` 并扣减 `pendingBombs[slotId]`。
    3. **2.5s 到期引爆（仅 S→C op 138 state=0）**：`Room.onTick` 到期后仅广播 `SetBombState(bombId, state=0)`（不发 op 148）；客户端 `0x00534B18 -> 0x00534338` 按 `bombId` 查找到 `bomb+0x90` 匹配的水泡并调用 `0x00565E9F` 引爆、清除棋盘格与危险区、调用 `vtable[+0x0c](0)` 释放槽位水泡容量，AI 随即恢复移动与下一轮放泡。实现见 `handler/room/SetBomberEventReq.kt` + `packet/room/SetBomberEvent.kt` + `server/Room.kt`；回归测试 `tools/test_ai_sync.py`。
- **局内炸箱子、爆出道具与拾取道具/障碍物闭环**：
    1. **炸毁可破坏方块上报（C→S op 126 DestroyBoxReq，`0x0054CE36`）**：水泡爆炸在 `0x00476F31` / `0x0047739B` 本地消除方块并生成隐藏道具（`0x00471F4B`）后，发送 5B `E1(gridX) E1(gridY) E1(slotId) E2(nextBoxState)`。服务端 `DestroyBoxReq.kt` 消费即可。
    2. **水泡炸出地面道具列表与角色状态上报（C→S op 154 BoxItemSpawnReport，`0x0053DD08` / `0x0046DB1D`）**：
        - `action = 5`（`0x0046DB1D`，`CGOGameBomber::OnDie`，4B `E2(slotId) E2(5)`）：角色死亡上报。服务端必须广播 **S→C op 159 BomberDie(slotId)**（`0x0054C378`）触发客户端播放死亡动画与淘汰；若玩家阵亡（slot 0），须依序广播 **S→C op 160 GameOver**（触发胜利跳舞）+ **S→C op 169 InitGameResult**（创建 `CGameResultDocument` 与结算 UI）+ **S→C op 150 GameResult**（填充结算金币经验与结果，触发结算弹窗 `0x00558EE1`）。缺少 169/150 会导致敌方角色永远在跳舞动作中循环、不弹结算窗。
        - `action = 4`（`0x0053DDD1`，变长）：`0x00565E9F` 爆炸结算末尾调用，发送 `E2(0) E2(4) E2(slotId) E2(count) + count * [E2(itemSeq) E2(gridY) E2(gridX) E1(flag)]`。
        - `action = 2 / 3`（`0x0046D986` / `0x0046D9F8`，4B `E2(slotId) E2(2/3)`）：角色受困/被救状态变更。
        - `action = 1`（`0x0046D5C3` / `0x0053DD81`，8B `E2(slotId) E2(1) E4(token)`）：实体/炸弹 token 同步。
        - `action = 0`（`0x00461399`，8B `E2(slotId) E2(0) E2(arg1) E2(arg2)`）：角色使用自救针等道具脱困。
        - ⚠️ 绝不可在收到 op 154 时未匹配 action 就盲目读取额外字节或无条件广播死人，否则炸箱子会误杀玩家、角色死亡时会因越界断线。
    3. **拾取地面道具（C→S op 110 EatItemReq → S→C op 143 EatItemResult）**：玩家或 AI 踩到道具时由 `CGameMsgCache::0x0053922F -> 0x00539B63` 发送 **C→S op 110**（20B = `E1(slotId) E1(gridX) E1(gridY) E2(itemSeq) E1(itemType) E4(0)×3 DecodeStr("")`；原 TW_5 误配为 144）。服务端必须广播 **S→C op 143 EatItemResult**（`0x0055A64B -> 0x0054BE87`，原 TW_5 误配为 358）：`E1(slotId) E1(gridX) E1(gridY) E2(itemSeq) E1(itemType) + [14B 金币袋扩展字段 E1(1) E1(0) E4(10) E4(0) E4(0)]`，客户端收到后通过 `0x00539313` 移出待确认队列并调用 `CBomber::OnEatItem (+0x180)` 销毁地面道具、生效属性加成。
    4. **踩地面障碍物（C→S op 111 EatObstacleReq → S→C op 144 EatObstacleResult）**：踩到地面陷阱/障碍物时由 `0x00539C38` 发送 **C→S op 111**（3B = `E1(slotId) E1(gridX) E1(gridY)`），服务端回显 **S→C op 144 EatObstacleResult**（`0x0054A684`，3B 同构）触发障碍物效果并移除障碍物。
