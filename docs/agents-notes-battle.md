- **对局阶段启动与水泡引爆机制**：
    1. **练习房/单人模式（tst.pcap stream 4）**：
       由 `Unk_92` (C→S op 92) 触发开始序列：`[111 AILevelChange, 112 GameStartMapInfo, 100 PracticeStartInfo, 136 PracticeBoard]`。
       - **S→C op 112 GameStartMapInfo（对局地图与出生点/角色槽位初始化，165 字节，CA.exe 0x51F29C）**：在开局时初始化随机数种子、主题 ID、加密地图名（`EncodeCipherStr` 经 `0x75daf0` 加密为 10B `"Cemetery04"`）、地图 ID、槽位出生点/角色/队伍标志等。若地图校验失败会弹出 `"InvalidMapReturnToLobby"` 退回大厅。实现见 `packet/room/GameStartMapInfo.kt`。
       - ⚠️ **这份包同时是「开局有几个电脑」的唯一名册**（2026-11-05 定论）：`CA.exe 0x0051F522` 读 `E1(slotCount)` 写到 `CGameContext+0x154`，`0x0051F52A` 起按 slotCount 循环读 N 条 17B 记录（`E1 slotIdx / E1 spawnPos / E1 bomber / E1 teamFlag / E1 state / E4×3`），每条第一个字节是**真实槽号**（客户端 `0x51F555/0x51F568/0x51F57B` 用它索引 `CGameContext+0x1e0+slot*0x1e4`），不是循环序号。`0x51F29C` 开头那段 10 次循环由 `0x51F2DF` 的 `je 0x51f408` 在练习房模式直接跳过。
         房间阶段 op 89 踢 AI 只清 `CPrepareStrategyAIBattle` 的本地槽位（`0x52B450 → 0x685ED9` 把 12B AI 配置清成 `(0,0,-1)`），**开局仍以 op112 名册为准**。旧实现写死 `Encode1(8)` + `for (0 until 8)` + `team = if (i==0) 0 else 8`，于是无论踢几个，开局都重建 7 个 AI。
         现已改为由 `StartGameReq` 传 `Room.slots` 中「`user != null || isAI`」的真实槽号给 `GameStartMapInfo(activeSlots=...)`，只发参战槽位、槽号不压缩重排；默认 `0..7` 保持与 `cap/practice_s2c_op112_167B_0.bin` 逐字节一致。参战判据必须与 `Room.resetMatchState()` 的 `hasSeat()` 一致，否则会出现服务端按 6 AI 判胜负、客户端显示 7 AI 的错位。回归测试 `GameStartMapInfoTest` 2 项。
         ⚠️ **出生点数量由地图决定**（2026-11-11）：真端 `CGameSession::LaunchGameStage` 取 `spawns = min(maxPlayer, 8)` 再洗 `0..spawns-1`（`GameSession.ts:1384`），4 人图只有 0..3 四个出生点。旧实现恒洗 `0..7`，4 人图会把玩家放到不存在的出生点。现由 `assignSpawnPositions(mapId, activeSlots)`（`packet/room/GameStartMapInfo.kt`）按参战槽号顺序取用 `MapCatalog.spawnPermutation(mapId)`，`Room.startGame()` 的 JP/TW/CN 分支同源；人数上限查 `MapCatalog.maxPlayerOf(mapId)`。
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
    4. **飞镖引爆水泡（C→S op 121 + op 108 → S→C op 138）**：
       - `op 121` (`0x00539CC2`，入队点 `0x00465EC8`) 8B = `E1(0) E2(起点Xpx) E2(起点Ypx) E1(方向) E2(射程px)`；
         `op 108` 6B = `E2(对象混淆ID) E1(gridX) E1(gridY) E2(delayMs)`，`delayMs` = `1200*射程px/1000 - 2*ping`（飞镖飞行时间）。
       - ⚠️ **飞镖命中绝不本地引爆**：`0x00465EC8` 命中分支只有 `CSendPacket_CtorWithOp(108)+Send`，全函数无 `sub_565E9F`；
         CGODart 只是本地飞行动画 + 射线命中判定。客户端在等服务端下令：`S→C op138` (`0x005481BD → 0x00534B18 → 0x00534338 → 0x00565E9F`)。
         曾误把 `0x0046DFAD`（`sub_5B67BA` vtable 调用，水泡自身帧更新撞障碍时本地引爆）当成飞镖路径，已纠正。
       - ⚠️ `op108` 的 `objId` 是 `sub_44B67A(obj+144)` 客户端混淆 ID，**不是** `bombToken`/`Room.bombs[].id`；
         必须按 `gridX/gridY` 反查服务端水泡，用 op139 回填过的 id 发 `SetBombState(id, 0)`。
       - 服务端实现 `Room.onObjectHit`（`server/Room.kt`）：命中格无水泡/越界不处理；找到即**立刻从 `bombs` 移除**
         （既是同一颗泡重复上报的去重——实测连发两镖会收到两条逐字节相同的 op108，也防 `onTick` 2.5s 二次引爆）；
         按 `delayMs` 延时广播 op138。`delayMs>0`（飞镖）广播**含发射者本人**（他本地没炸）；
         `delayMs==0`（客户端已本地引爆的路径）排除发射者，只同步其它端。
       - handler：`handler/room/ThrowDartReq.kt`（op121，仅解析）、`handler/room/ObjectHitReq.kt`（op108）；
         `EN_18_recv.yaml` 里旧的 `PlayEmotionReq: 108`（真端 TS 另一版本语义）已注释。
         回归测试 `ThrowDartReqTest` / `ObjectHitReqTest` / `DartDetonationTest`。
- **局内炸箱子、爆出道具与拾取道具/障碍物闭环**：
    1. **炸毁可破坏方块上报（C→S op 126 DestroyBoxReq，`0x0054CE36`）**：水泡爆炸在 `0x00476F31` / `0x0047739B` 本地消除方块并生成隐藏道具（`0x00471F4B`）后，发送 5B `E1(gridX) E1(gridY) E1(slotId) E2(nextBoxState)`。服务端 `DestroyBoxReq.kt` 消费即可。
    2. **水泡炸出地面道具列表与角色状态上报（C→S op 154 BoxItemSpawnReport，`0x0053DD08` / `0x0046DB1D`）**：
        - `action = 5`（`0x0046DB1D`，`CGOGameBomber::OnDie`，4B `E2(slotId) E2(5)`）：角色死亡上报。服务端必须广播 **S→C op 159 BomberDie(slotId)**（`0x0054C378`）触发客户端播放死亡动画与淘汰；若玩家阵亡（slot 0），须依序广播 **S→C op 169 InitGameResult**（创建 `CGameResultDocument` 与结算 UI）+ **S→C op 150 GameResult**（填充结算金币经验与结果，触发结算弹窗 `0x00558EE1`）。缺少 169/150 不弹结算窗。
        - `action = 4`（`0x0053DDD1`，变长）：`0x00565E9F` 爆炸结算末尾调用，发送 `E2(0) E2(4) E2(slotId) E2(count) + count * [E2(itemSeq) E2(gridY) E2(gridX) E1(flag)]`。
        - `action = 2 / 3`（`0x0046D986` / `0x0046D9F8`，4B `E2(slotId) E2(2/3)`）：角色受困/被救状态变更。
        - `action = 1`（`0x0046D5C3` / `0x0053DD81`，8B `E2(slotId) E2(1) E4(token)`）：实体/炸弹 token 同步。
        - `action = 0`（`0x00461399`，8B `E2(slotId) E2(0) E2(arg1) E2(arg2)`）：角色使用自救针等道具脱困。
        - ⚠️ 绝不可在收到 op 154 时未匹配 action 就盲目读取额外字节或无条件广播死人，否则炸箱子会误杀玩家、角色死亡时会因越界断线。
    3. **拾取地面道具（C→S op 110 EatItemReq → S→C op 143 EatItemResult）**：玩家或 AI 踩到道具时由 `CGameMsgCache::0x0053922F -> 0x00539B63` 发送 **C→S op 110**（20B = `E1(slotId) E1(gridX) E1(gridY) E2(itemSeq) E1(itemType) E4(0)×3 DecodeStr("")`；原 TW_5 误配为 144）。服务端必须广播 **S→C op 143 EatItemResult**（`0x0055A64B -> 0x0054BE87`，原 TW_5 误配为 358）：`E1(slotId) E1(gridX) E1(gridY) E2(itemSeq) E1(itemType) + [14B 金币袋扩展字段 E1(1) E1(0) E4(10) E4(0) E4(0)]`，客户端收到后通过 `0x00539313` 移出待确认队列并调用 `CBomber::OnEatItem (+0x180)` 销毁地面道具、生效属性加成。
    4. **踩地面障碍物（C→S op 111 EatObstacleReq → S→C op 144 EatObstacleResult）**：踩到地面陷阱/障碍物时由 `0x00539C38` 发送 **C→S op 111**（3B = `E1(slotId) E1(gridX) E1(gridY)`），服务端回显 **S→C op 144 EatObstacleResult**（`0x0054A684`，3B 同构）触发障碍物效果并移除障碍物。
- **推动可移动箱子（C→S op 109 PushMovableBoxReq，2026-11-04 逆向 CA.exe 确认；复现脚本 `tools/rev_op109.py`）**：
    1. **触发链路**：队列由 `0x005393C2`（7 参数：slot + 5 字段 + 立即发送标志）填充，唯一调用者 `0x0054CABA` 是角色移动/碰撞时**尝试推动可移动方块**的处理（`0x005618B3` / `0x0055E32C` / `0x0055D9C2` / `0x0055C845` / `0x0055E42F` 校验格子与像素坐标 `*0x28`），由本地帧循环 `0x0046D878 → 0x0054EC0D` 驱动。⚠️ 该函数**只做校验 + 入队，没有任何本地位移调用**（0x0054CABA 尾部即 `0x5393C2` 后 `ret 0x10`）；`CAIGoalPushBlock` 只是 AI 决策层，所谓「客户端已完成本地预测」的旧结论是**错的**。
    2. **载荷 7B = `E2(boxSeq) E1(gridX) E1(gridY) E1(targetX) E1(targetY) E1(slotId)`**：字段宽度用同一个 `[cache+0xD8]` COutPacket 对象的 C→S op 110（`0x00539BAE`，`E1`×3 走虚表 `+0x20`、`E2(itemSeq)` 走 `+0x28`）交叉验证，编码点 `0x00539D73` 本身也可见 `movzx cx,[edi+4]`(E2) 与 5 次 `+0x20`(E1)。实测日志 `A5 60 59 58 59 59 5C` 经 `xor 0x5A / 0xA569` 解出 `(1, 1,3, 1,4, 0)`，即把箱子从 (1,3) 推到 (1,4)。末字节是客户端 CGameMsgCache 待确认队列的槽位（OnTick 循环下标 0..7），不是玩家槽位。
    3. **服务端必须广播 S→C op 142 `MovableBoxMove`**（2026-11-05 定论）：局内 S→C 分发器入口 `0x0055A4D8`（`Decode2 0x78F090` 读 2B opcode）在 `141→0x54BD14(DetonateBomb)` 与 `143→0x54BE87(EatItemResult)` 之间，`142` 走 `0x54A5F9`：依序 `Decode2 + 5×Decode1`，然后 `call 0x565CE0` —— **该函数是全 `.text` 里唯一移动可移动箱子的实现，且全文件只有 `0x0054A668`（即 0x54A5F9 内）这一个调用者**，证明箱子位移 100% 由服务端驱动。`0x565CE0(map, idx, x1,y1, x2,y2, slot)`：`map+idx*4+0x19A23C` 取 `CGOTileMovableBox`（idx 即 op109 的 boxSeq）→ 要求 `[box+0x88]/[box+0x8C]` 等于 (x1,y1) → 清源格 `0x5606C6`/`0x5608CF(_,0)`、占目标格 `0x560693`/`0x5608CF(_,1)`、虚表 `+0x64` 更新像素位；失败则 `0x549C35(slot,2,-1)` 回 C→S op 149 回滚上报。广播载荷与 op109 **逐字节同构**，原样回放即可（含推箱者本人）。实现：`packet/room/MovableBoxMove.kt`(`EN_18_send.yaml: MovableBoxMove: 142`)、`server/Room.kt` `onMovableBoxPush` 去重 + `BC`。**去重必要**：持续推箱时 OnTick 每 >5 帧重发同一动作，而 `0x565CE0` 会校验「箱子仍在 (x1,y1)」，重复广播第二次必然失败并触发回滚包，故同 boxSeq+同 src/dst 的上报在 `movableBoxDedupeWindowMs`(默认 400ms) 窗口内只播一次；窗口必须有限，否则某端漏包导致箱子没动时玩家再推也无法重播自愈、箱子会永久卡死。⚠️ 旧 TW_5 的 S→C op 155 / 357 在 EN 客户端没有任何分发分支，发了会被静默丢弃；EN 也没有 C→S op 143 发包点。回归测试 `src/test/kotlin/bnbemu/netty/PushMovableBoxTest.kt` 7 项（handler 绑定 / 7B 实测样本解码 / 残包防御 / 广播 opcode=142 / 广播载荷逐字节对比 / 重复上报不重播 + 连续推动可播 / 越界丢弃）。
- **对局结束判定（赢/输都是服务端下令，2026-11-05 二次修正：改用 op 112 action=0x60）**：
    1. **op154 action=5 只对真人玩家成立，AI 阵亡永不上报**（2026-11-05 定论，含客户端铁证）：
       死亡链是 `0x46897x`（CBomber 帧更新：`0x45CE9E` 命中判定 → `bomber+0x2AC4` 倒计时归零）
       → `vtable+0x1E0 = 0x46DAE4`（对所有 bomber 都会调用）→ **`0x46DB01` 先调 `0x45FBD3`**，
       `je 0x46DB5C` 不通过就整个跳过发包。`0x45FBD3` 的判据是 `0 <= bomber+0x4D0 < 2`；
       `+0x4D0` 就是 `0x45F0xx` 调试格式化串 `0x892838 "m_selfidx = "` 所指的**网络玩家序号**
       （相邻的 `+0x4DC` 是 `0x89282C "m_faislot"` = AI 槽号）。bomber 初始化（`0x4680xx`）把
       `m_selfidx` 置 **-1**，真人序号只在 `0x639FE3` 的"遍历玩家"循环里经 `0x45FDDD` 写入 0/1。
       → **AI 的 m_selfidx 恒 -1，闸门永不通过**。实测日志（1510 行 / 72 个 op154）里 action 只有
       `4 / 1 / 0`，**没有 2 / 3 / 5**，所以"AI 被困/被救会上报"的推测也是错的，别再拿 action 2 兜底。
    2. **真正的结束信号：C→S op 112 action = 0x60**（`CA.exe 0x54B380~0x54B450`，帧更新每帧跑）：
       ```c
       int cnt[9] = {0};                                  // [ebp-0x5C], 0x24 = 9*4
       for (b in bombers) if (b->vtable[0xAC]())  ++cnt[b->+0x84];   // +0x84 = 队伍号 (0..8)
       if (countNonEmpty(cnt) > 1) return;                // 还有两队以上 → 继续打
       for (b in bombers) if (b->+0x8 && b->vtable[0x150]() == 0)
           0x54A773(b);                                   // 发 op112: E1(0x60|slot)+E2(x)+E2(y)
       ```
       `vtable[0x150] = 0x45FBA0`（`m_selfidx<0 && m_faislot==0` 才跳过）→ 真人和 AI 的存活者都会发。
       **客户端每帧重发，直到服务端下发结算**：实测杀光 AI 后连发数百条同一条 `3A A5 39 A7 6E`
       （slot 0 / x=80 / y=519），同时其它局内包全部停摆 —— 就是"对局卡住不动"的真身。
       → `handler/room/BomberActionReq.kt` 已接 `0x60 -> Room.onLastTeamStanding(slotId)`，
       `Room.onLastTeamStanding` 按"存活方是真人槽还是 AI 槽"决定谁胜，用 `gameOverSent` 去重，
       补记落败方 `matchState = DEAD`，然后走 `op169 → op150` 结算二连。
    3. **op154 action=5 路径保留**，用于玩家自己阵亡（以及将来真正的多真人房）：
       每个 `Slot` 带三态 `matchState`（`OUT_OF_MATCH / ALIVE / DEAD`，开局 `Room.startGame → resetMatchState`
       按「座位上有人?ALIVE:OUT_OF_MATCH」重置）。`OUT_OF_MATCH` 同时充当 `onBomberDie` 的守卫，
       挡掉给空槽广播 op 159 的错位包。
        - **人类全灭** → AI 胜，`op 150 winnerTeam = 1`（胜利方槽位取一个仍活着的 AI）；
        - **AI 全灭 && 人类存活** → **玩家胜**，`op 150 winnerTeam = 0`（玩家槽位 0）；
        - 同一槽位的重复上报只广播一次 op 159，`gameOverSent` 保证结算只下发一次，1.5s 延迟
          （`Room.gameOverDelayMs`）是为了让阵亡动画播完；
        - 顺序 `op 169 InitGameResult → op 150 GameResult`，缺 169/150 不弹结算窗。
    4. ⚠️ **走 action=0x60 路径时不要补发 op 159**：AI 的阵亡动画是客户端本地播的（OnDie 本地调用，
       只是发包被 m_selfidx 卡住），服务端再发 159 会让死亡动作重播。`onLastTeamStanding` 只补记状态。
    4.1 **结果页字段语义（2026-11-08 逆向定案，`packet/room/GameResult.kt`）**——此前这两个字段都是按布尔量猜的，全都猜错：
       - `op150` 头第 1 字节 `gameType`（doc+6612）**必须为 0**。结果页装配函数 `0x558EE1` 开头即
         `if (*(dword *)sub_53E1B4(doc) != 0) → new CGOMissionGlyph + 贴图 "Game/Mission"`（`0x44DAE3/0x44DB94`），
         于是普通练习房的结果页顶部会盖一枚 **"MISSION PERFECT!!" 通关印章**。旧实现写死 `Encode1(1)`，这就是它的来源。
       - 每个槽记录的 `+68`（Human 第 2 个字段 / AI 第 1 个字段）**不是 win/loss 布尔，而是"结果标签贴图帧索引"（0..4）**：
         列 2 绘制 `0x46F544 → 0x53E7CF(target, x, y, slot+68)`，实测帧 **0 = "YOU WIN!!"、1 = "PERFECT!!"**。
         旧实现（真人 `winnerTeam==0?1:0`、AI 恒 0）导致真人显示 PERFECT!!、AI 显示 YOU WIN!! —— **两行都是"赢"的帧**，
         这就是"杀死电脑对手后电脑对手仍显示胜利"的原因。输的一方要写 `LOSE` 帧（当前取 2，待实机复核）。
       - 僵尸模式 `0x5B574B` 把 `slot+68` 与 `header.winnerTeam` 按 `(0,0)/(1,2)` 配对比较 → 该字段同时承担"阵营/结果编号"语义，
         绝不可当 0/1 布尔复用。
       - 列内容：`doc+6624`（头第 4 字节）== 0 → 第 4 列画 `slot+72/+76`（KILLS/SAVES）；非 0 → 画 `slot+84/+92`（EXP/LUCCI）。
       - **EXP% / LUCCI 两列的数据源（2026-11-08 补充）**：列绘制在 `0x46F544` 的 case 5 / case 6，
         且**只对 `slotType==1` 的真人槽生效**（AI 行这两格天生是空的）：
         · case 5 → `0x53E617(doc, slot)` = `min(槽+124 + doc+6251, 槽+128)`，单位千分比
           （`0x53F998` 按 `/10` + `%10` 拆成 "xx.x" 再配一个 "%" 字形，1000 特判成 "100"）。
           **`+128` 是总闸**：它为 0 则这一列永远 `00.0%`。`+124` = 战前进度，`+128` = 战后进度，
           `+124 > +128` 时按升级回卷（+1000 取模）处理。`doc+6251` 不是包字段，是结果窗计时器
           （`0x46F38F` 每帧写 `count-66`）驱动的涨条动画，所以看起来是从战前 % 涨到战后 %。
           `+148`（E2）只在 feature 36 分支里作为"带正负号的 EXP"读，正常不生效。
         · case 6 → `0x53E880(..., 槽+152, ...)`，**`+152`（E4，有符号）= LUCCI 数字**，负值会画减号。
         · 旧实现把 50/100 写进 `+84/+92`，而默认列布局（头第 4 字节 == 0）根本不看这两个字段，
           所以结果页恒显示 `00.0%` / `000000`。现在 `+124/+128/+152` 才是显示源。
         · 回归测试：`GameOverTest.testGameResultResultScreenFields` / `testGameResultAiWinSwapsFrames`
           （包长 119B、gameType==0、胜负帧、`+124/+128/+152` 偏移全部钉死）。
    4.2 **杀 AI 为什么没有死亡音，官方是怎么做到的（2026-11-11 定案）**：
       - **死亡音 0x2A 只有一个来源**：全 `.text` 里 `sub_4DEDAD(..., 42, ...)` 仅两处，
         都在 op146 处理器 `0x0054DDD1` 的收尾（`case 4` 死亡+掉落 / `case 5` 死亡名单）。
         op159 播的是 **0x37**（`0x0054C378: sub_4DEDAD(..., 55, ...)`），实测无声 ——
         「音效是 BomberDie 触发的」这个印象，实际来自同一批下发里的 op146 state=5。
       - **客户端对 AI 一条濒死包都不发**（实测整局 72 个 op154，action 只有 1 / 4）：
         · action 5 死亡上报 → 闸门 `IsLocalPlayerIndex_0or1`（`0x45FBD3`：`0 <= bomber+0x4D0 < 2`），
           AI 的 `m_selfidx` 恒 -1，永不过；
         · action 2/3 被困/被救 → 闸门 `IsNonLocal_And_SlotTypeZero`（`0x45FBA0`：只有
           `m_selfidx<0 && slotType==0` 才跳过），AI 座位 slotType 非 0 能过 —— **这是 AI 唯一的濒死信号**。
       - **本模拟器落地（唯一路径）**：`tools/patch_ai_die.py` 把 `0x0046DB08` 的 `74 52`（`je 0x46DB5C`，
         即 `IsLocalPlayerIndex_0or1` 不通过就整段跳过发包）改成 `90 90`。打完补丁后**每个 bomber
         （含 AI）阵亡都发 C→S op154 action=5**，走的正是人类那条路 → 服务端 `Room.onBomberDie`
         广播 159 + op146 state=5 → 客户端播 0x2A。时机精确、每个 AI 一声，与官方客户端等价；
         对人类玩家行为零变化。

    5. **op 160 = `ReviveBomber`（复活某个 bomber）：
       分发表 `0x55A6A2`（`sub eax,0x90` / `cmp 0x1A` / `movzx byte[0x55AA48+..]` / `jmp [0x55AA08+idx*4]`）
       里 `0xA0 → 0x55A719 → call 0x55345D`。处理器：
       · `Decode1(slot)` → `0x5347EE(session, slot)` → `0x533D5C(slot)` 取 bomber
       · `0x5347EE` 内部：`vtable+0xC(1)` 置活 → `0x4606C2`（清陷阱/水泡、`vtable+0x5C(0x2B)` 置状态 43）
       · 用字符串表 key `RebirthToGhost`（`0x8A4CD0`，fx.idd 文案 `"%s has been resurrected!"`）
         拼出 `"XXX has been resurrected!"`，经 `0x4E3CBE → vtable+0x130(0,msg)` 发到聊天/公告栏
       · 遍历 UI 观察者 `[this+0x3BF8]` 回调 `vtable+0x38(slot)`
       旁证：key `SomeoneResurecction`（`0x8A8558`，`0x57BE4x` 的救援币路径）打的是同一句文案。
       → 包类已按真实语义定名 `packet/room/ReviveBomber.kt`（`EN_18_send.yaml: ReviveBomber: 160`），
         作为**复活币**的协议基础；服务端入口 `Room.reviveBomber(slotId)`（校验 → `matchState = ALIVE`
         → 广播 op160），目前无调用点。接入复活币时要补：道具校验/扣减 + 背包同步 + 复活后的位置与保护期。
    6. **排查用日志**：`[Room] BomberDie slot=N (人类/AI) | ...` 与 `[Room] 对局结束: 玩家胜/AI 胜 | winnerSlot=N`。
       若杀光 AI 后只看到几百条 `BomberActionReq act=0x60 slot=0` 而没有 `对局结束`，说明 handler 没绑上 op 112。
    7. **回归测试**：`src/test/kotlin/bnbemu/netty/GameOverTest.kt`（20 项：action=0x60 handler 绑定 /
       实测样本 `3A A5 39 A7 6E` 端到端结算 / 不重播 159 / 洪水去重 / 结算后迟到包忽略 / 空槽忽略 /
       结算不含 op160 / `reviveBomber` 复活 DEAD 槽位并下发 op160、拒绝 ALIVE·空槽·越界·已结算）；
       端到端脚本 `tools/test_game_win.py`（启动模拟器后跑）。
- **C→S op 107 是「水泡移动（抛掷/踢出）请求」，回应 S→C op 141，见第 5 条）**：
    1. **发包点**：全 `.text` 只有 `0x00539DF6` 一处 `push 0x6b` + `COutPacket::Init(0x64EE5D)`，
       所在函数 `0x00539DC8` 由 `CGameMsgCache::OnTick`(`0x0053A700`) 的 `0x0053A7DD` 调用 ——
       和 op109 推箱子同一套「待确认队列 + 每帧 flush」机制（队列数组 `[cache+0x80] + idx*0x20`，idx 0..7，
       队列填充函数 `0x00539494`，发完 `and [entry],0` 清 active）。
    2. **载荷 9B = `E1((kind<<4)|slot) E2(bombId) E1(fromX) E1(fromY) E1(toX) E1(toY) E2(ms)`**
       宽度由 COutPacket 虚表 `0x8B66F4` 交叉验证：`+0x20`/`+0x24` 写 1B、`+0x28` 写 2B、`+0x2C` 写 4B（共 9B，与实测 dump 长度吻合）。
       `bombId` 就是服务端在 op139 里回填的 `bomb->m_nBombId (+0x90)`（== 我们的 bombToken）。
    3. **唯一两个入队调用点都在「水泡滑行」逻辑里**：`0x0054CDEB`（`0x0054CCBC`，由帧循环
       `0x0054EC0D → 0x0054CDF8` 驱动，先 `0x005635FB` 沿朝向 trace 出目标格，`kind = 方向+4`）与
       `0x0046E6D7`（bomber 帧更新里对 `bomb+0x9c/+0xa8`（格子坐标）的同类处理，`kind = bomber->vtable[0xb4]`）。
       两处都是 `from = 水泡当前格`、`to = trace 出的目标格`。
    4. **实测解码**（`tools/rev_op107.py` 第 6 节）：
    5. **服务端必须回 S→C op 141 BombKickThrow**：
       水泡位移的唯一驱动是 **S→C op 141 (BombKickThrow, `0x54BD14`)**
       的 action 分支（反汇编逐指令核实，0x78F030=ReadUInt8×5 + 0x78F090=ReadUInt16×1，共 **7 字节**）：
       - 载荷 = `E1((action<<4)|slot) E2(bombId) E1(oldX) E1(oldY) E1(x) E1(y)`
         （oldX 仅与 bomb+0x9C 校验、oldY 读取丢弃；x/y 即目标格 = op107 的 to 字段）
       - `action > 3` → `sub_4598AB(bomb, x, y, action-4)`：置 moving(+292)=1、方向(+304)=action-4，
         帧更新 `sub_459C69` 逐帧把精灵滑到目标格；`action 0..3` → `sub_459935`：重定位/抛掷落地
         （+280=1，帧更新 `sub_459EEB` 播弧线）。两函数全 exe 只有 op141 处理器与 op139 确认链
         （`sub_534C3D`）两个调用者 —— **客户端没有任何本地位移预测，不回 op141 水泡纹丝不动**。
       - **action 高 4 位与 op107 的 kind 一一对应**（kind=方向+4 踢出 / 0..3 触碰推行）；
         回显载荷 = op107 9B 去掉末尾 E2(ms) 的**前 7 字节原样**，广播给所有人含踢泡者本人
         （与 op109→op142 推箱子同构）。
       - 坐标字段序全链一致：`(gridX 行 0..12, gridY 列 0..14)`；方向增量表
         `dword_9886E4/9886E8[2*dir] = (-1,0,1,0)/(0,-1,0,1)` 交叉验证（kind=6→dir=2 走 gridY+1，
         与用户实测样本 (2,3)→(2,4) 吻合）。
       - 旧候选已证伪：op138 SetBombState 状态机只有 0 引爆/1 激活，无移动状态；
         笔记里的"op145 14 分支 action 表"实为 **op 146**（`sub_54DDD1`）的 bomber 动作同步
         （case 0..13），驱动的是角色不是水泡。
       - 实现：`handler/room/BombKickThrowReq.kt` → `Room.onBombMove`（400ms 同动作去重 + 更新水泡位置/倒计时
         + 广播 op141）；`packet/room/BombKickThrow.kt` 。回归测试 `BombMoveTest.kt` 10 项
- **对局结束回房间：S→C op 155 BackToRoom（2026-11-08 逆向定案，`packet/room/BackToRoom.kt`）**：
    1. **客户端不会自己回房**。结果窗是纯本地 UI：`0x005597FD CheckResultDoc_AndShow` 每帧把 `doc+6520`
       减 30，归零才调 `0x00558EE1` 装配弹窗；而 `doc+6520` 由 `0x00559F8B → 0x0053E181(delayMs-1500)`
       一次性设置 —— **op150 的 delayMs 只决定「多久后弹结果窗」，与「多久后回房」无关**。
       弹窗之后没有任何计时器关窗（`0x0046F38F` 只跑涨条动画），所以只发 169/150 会让客户端永久停在结果页。
    2. **唯一出口**：全 `.text` 里能在游戏阶段（stage 13）改阶段的入口只有 `0x0054FC14` 末尾
       `sub_68DC53(CStageManager, 12)`（`0x68DC53` 是全 exe 唯一的「请求切阶段」入口），
       它的唯一调用者是局内分发器 `0x0055A4D8` 的 `case 155`：
       `sub_67CDCE()` 为真（僵尸/团战）→ `0x0054A221` → 回 stage 11/20 并清 UI 子窗；否则 → `0x0054FC14` → 回 stage 12 房间。
    3. **op 97 走不通**：op97 处理器 `0x5CDD15` 只挂在「选频道/房间」阶段虚表（`0x8AF0B4` 表 index 48）上，
       局内分发器没有 97 分支，在 stage 13 收到会被静默丢弃（它接受的目标值仅 12/18/16/17，见 `0x4F748F`）。
    4. **载荷**：`E1(chState)` + 固定 3 条 `{ E1 flag; flag!=0 → 3B 槽位状态{state,x,y} | flag==0 → E4 id + CRoomInfo(房名等) + 3B + E1 + Str + 用户列表 }`。
       进入时客户端先本地清空 8 个槽位（`state&0xE3` 只清 0x1C，**保留 0x01/0x02 的房主位**；bomber/color/map=-1），
       特殊模式再清 10 条频道记录。3 字节里的 `state` 与 op105 `Slot.meta[0]` 同一套（0x01 关闭 / **0x02 房主** / 0x04 准备）。
       写入侧 `0x6865AC` 只用传入字节并自己重算 key/校验和，传什么都安全（`dword_A373C8` 只在**读回**校验失败时触发）。
    5. **实机验证（2026-11-08 第一轮）**：只发 155 能回房间、AI 也还在，但
       **「开始」按钮退化成「准备」= 房主位丢失** —— 因为旧实现把 3 条状态一律写 0，把槽 0 的 0x02 抹掉了。
       现在 `BackToRoom` 默认按 `Room.slots[0..2].state` 下发（槽 0 = FLAG_CHIEF）。
       另一条必修项：155 只把客户端「切回 stage 12」，房间对象/会话是空的 ——
       `Room.backToRoom` 默认在 155 之后 `roomChainDelayMs`(400ms) 补推整条房间链
       （`server/RoomEntryChain.kt`，与 C→S op 75 的回应同一串 87/83/4×2/113/100/108/106×8/114·104·105·103）。
    6. **对局结束回房间弹 "InvalidLevelPlayGame" 并踢回大厅（2026-11-09 根因定案并修复）**：
       - **现象**：从大厅进房间时等级正常（5级）不报错，但对局结束通过 `op 155 BackToRoom` 返回房间时弹出 `InvalidLevelPlayGame` 并被踢回大厅。
       - **根因**：`CGameStage` 处理 `S→C op 150 GameResult` 的虚函数 `0x00559854`（此前 `CA.exe.c` 因 `control flows out of bounds` 漏反编译）在解析真人槽记录（`sub_541205`，`esi = doc + 244 * slot + 6648`）后：
         · 用 `word[esi + 0x88]`（`+136`）调用 `sub_6E4AAD(CUserDynamicInfo, localPlayerIdx, level)` 无条件覆写本机玩家等级（`CUDI + 12*idx + 24`）；
         · 用 `dword[esi + 0x84]`（`+132`）更新房间槽位对象（`edi + 0x6C`）及 `CUserMap`（`sub_4E03C1`）的玩家等级；
         · 同段还用 `+84/+88/+100/+104` 更新 `CLadderInfo` 与房间槽天梯字段，用 `+240/+241/+242`（`0x6E4C72/0x6E4C8C/0x6E4CA6`）更新 `CUserDynamicInfo` 的胜/负/平场次。
       - 旧 `GameResult.kt` 将 `+132` 和 `+136` 硬编码为 `0`，导致客户端一收到 `op 150` 就把 `CUserDynamicInfo` 里的玩家等级从 `5` 抹成 `0`；随后收到 `op 155` 重建 Stage 12 房间（`sub_525250`）时，`sub_67CAC3(1)` 等级门禁读出 `0 < 1` 触发 `InvalidLevelPlayGame` 并踢回大厅（而补推的 `sendRoomEntryChain` 延迟 400ms 且 `op 20` 只挂在 `CLobbyStage` 上，救不回来）。
       - **修复**：`GameResult.kt` 中真人槽 `+132` 与 `+136` 改为写入 `(slot?.user?.level ?: 5).coerceAtLeast(1)`，`+240/+241/+242` 写入玩家胜/负/平场次。回归测试 `GameOverTest.testGameResultPreservesCustomPlayerLevelAndClampsZero`。
    7. **时序/调参**：`Room.resultReturnDelayMs`（默认 3.5s，负数关闭自动发送）、
       `Room.reenterRoomAfterBack`（默认 true）、`Room.roomChainDelayMs`（默认 400ms）。
       聊天框调试命令（debugMode）：`/back`（155+房间链）、`/back raw`（只 155）、
       `/back <s0> <s1> <s2>`（指定槽位状态字节，试房主位/准备位）、`/back chain`（只补房间链）、
       `/backchain on|off`、`/backdelay <ms>`。回归测试 `BackToRoomTest.kt` 9 项。
