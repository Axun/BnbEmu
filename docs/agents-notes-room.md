- **设置电脑敌人等级（op 88 AILevelChangeReq，2026-09-10 逆向 CA.exe 确认）**：在房间/练习房界面调整
  AI 难度等级时由客户端发出 **op 88 (0x58)**（CA.exe 中 `CPrepareStrategyAIBattle::OnButtonClick`
  触发，原 TW_5 为 op 110）。body = `E1(chief/f1) + E1(slotIdx) + E1(newLevel) + E1(flags/f4)`（4 字节）；
  服务器更新 `Room.slots[slotIdx].AILevel = newLevel` 并下发 **op 103 PracticeSetSlotAI**
  （`E2(slotIdx) + E1Bool(isAI) + E2(newLevel) + E2(flags)`，7 字节），`CPrepareStrategyAIBattle::OnPacket`
  (`0x52d188` -> Case 103 `0x52b39a`) 收到后调用 `0x52b048`/`0x52b0ad` 刷新槽位 UI 并执行
  `and dword ptr [esi + 0x3250], 0` 清除等待锁。
- **踢出电脑敌人 / 打开关闭槽位（op 89 OpenCloseSlotReq，2026-09-10 逆向 CA.exe 确认）**：在房间/练习房
  界面踢出 AI 或切换槽位开关时由客户端发出 **op 89 (0x59)**（CA.exe 中 `0x4f6866` / `0x51c7ea` 触发，
  原 TW_5 为 op 100）。body = `E1((slotIdx << 4) | (action & 0x0F))`（1 字节）；
  服务器重置该槽位 AI 状态并下发 **op 106 PracticeSlotReady**（`E1((slotIdx << 4) | state)`，清除
  `CSlotStage` 的 `[esi + 0x2f64]` 等待锁 2）与 **op 103 PracticeSetSlotAI(isAI=false)**（`E2(slotIdx) + E1Bool(0) + E2(0) + E2(0)`，
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
- **房间内切换队伍/颜色（C→S op 86 / S→C op 104，2026-11-04 反编译 CA.exe 定论）**：
  ⚠️ 旧 `EN_18_send.yaml` 把应答 `SelectColor` 绑到 **108**，是沿袭 TW_5_send.yaml 的历史误标；
  `tst_flow4.txt:105` 里标成 `op=108 SelectColor` 的那一帧其实是 `E2(0) E2(0) E4(172)` 的**选图回应**
  （`cap/practice_s2c_op108_10B_0.bin`，mapId=172 = Cemetery 组随机）。另有 `PracticeColor` 函数/键
  同源于此误标，已正名为 `PracticeSelectMap`（改为复用结构化 `SelectMap(mapId=172)`）。
  1. **C→S op 86（0x56）**：两个等价发包点 —— `CSlotStage` `0x4F675F`（`call 0x64EE1C` 在 `0x4F677B`）
     与 `CPrepareStage` `0x51C67F`（`0x51C69F`）。body 仅 1B：
     `E1((arg1 << 4) | (arg2 & 0x0F))`，`0x4F6784`/`0x51C6A8` 计算，`0x78D040` = Encode1。
     arg1 = 槽位、arg2 = 颜色/队伍值；`0x51D550` 把 4 个队伍按钮映射为 0/3/5/6，
     `0x4F6AA9` 的循环换色用 `(color+10) mod 9`（即 0..8）。发包成功后客户端立刻置等待锁 **6**
     （`CSlotStage [esi+0x2f64]=6` @`0x4F67CB`；`CPrepareStage [esi+0x2f78]=6` @`0x51C700`）。
  2. **S→C op 104（0x68）**：`CSlotStage::OnPacket` `0x4F480C` 的 `cmp eax,0x68; je 0x4F4885` →
     `0x4EFB48`；`CPrepareStage::OnPacket` `0x52A949` 的 case 104 → `0x51F196`。两者都：
     `Decode1` → `slot=b>>4` / `color=b&0xF` → 用 `0x687914` 取玩家 3B meta、把 `meta[2]` 低 5 位改成
     新颜色、`0x687169` 回写 → `0x4D5C87(slot, color)` 刷新槽位 UI → `0x687CBB` 校验通过后
     `and [esi+0x2f64 / +0x2f78], 0` **清除等待锁 6** → 重新启用按钮。
     **缺此应答 = 房间永久卡在「点颜色没反应」**（与 op 87/89 的锁 5/2 同机制）。
  3. **包体长度按舞台不同**：`0x4EFB48`（CSlotStage，练习/AI 房）只读 1 个 Decode1；
     `0x51F196`（CPrepareStage，普通房）连读 3 个：第 2 字节 → `0x6873D5(slot, attr)`，
     第 3 字节 → `(slot2<<4)|attr2`，`slot2==0xF` 时跳过。`0x6873D5` 写
     `CGameContext+0x1e0 + slot*0x1e4 + 0x14`（getter `0x687C78`，`0x4F5664` 处有置 1 的用例，
     疑为房主/队伍标志，非颜色本身）。`InPacket::Decode1` `0x78F030` 越界只返回 0、不抛异常，
     所以 1B 包在普通房也能解锁；`packet/room/SelectColor.kt` 提供 `extended=true` 的 3B 变体备普通房使用。
     抓包基准 `cap/practice_s2c_op104_3B_0..7.bin` 全是 `a5 01 <1B>`：
     `5a,42,72,62,12,02,32,22` → `0x00,0x18,0x28,...,0x78`（槽 0 颜色 0，槽 1..7 颜色 8）。
  4. **op 98 与颜色无关**：EN 客户端唯一的 `push 0x62` 发包点 `0x4F0634`（函数 `0x4F0559`，
     由 UI 事件回调 `0x4F0731` 触发）。包体是 `E1(slot) E2(count) + count * (E4,E4,E4)`，
     即 1B 槽位号 + 2B 条数 + N 条 12B 三元组；「带记录列表的上报包」只是对这个形状的描述，
     不是客户端里的正式名字。`slot` 来自 `0x687CA5`（须满足 `0<=slot<[this+0x2f5c]`）；
     列表由 `this+0x8ff0` 的 UI 状态容器经虚调用 `[vt+0xc]` / `[vt+0x10]` 收集，
     `0x4DBA76` 每次往 vector push 12B（`movsd`×3 + `add [ecx+4],0xc`），
     每条内容为 `(类别, idA, idB)`：`0x4DBB6C` 先塞 9 条 type=0x0C、12 条 type=0x13，
     再从子控件取 `0x44862D`(类别) / `[obj+0x44]` / `[obj+0x08]` 两个 ID。
     `0x78D060/0x78D080/0x78D0A0` = COutPacket Encode1/2/4
     （虚表 `0x8B66F4` 的 `+0x44/+0x48/+0x4C` → `0x78DAF0/0x78DB80/0x78DC10`，分别写 1/2/4B）。
     具体业务含义（疑为界面/装扮/快捷栏条目状态清单）尚未从客户端字符串或抓包钉死；
     同函数还会在角色不一致时顺带发 op 87（`0x4F67E9`），因此与房间设置操作同源但不同包。
     `TW_5/CN_12/JP_17_recv.yaml` 的 `SelectColorReq: 98` 是旧版编号（该版 97/98/99/100
     = 选图/选色/换角色/开关槽位），EN 版重编号为 85/86/87/89，不适用于 EN。
     S→C 方向另有 op 86（大厅期周期推送，25B，见 `tst_flow4.txt:62`），与 C→S 的选色请求互不冲突。
  实现见 `handler/room/SelectColorReq.kt` + `packet/room/SelectColor.kt`；单元测试见 `SelectColorTest.kt`。
- **进练习房（op 75，2026-09-01 已实现，tst.pcap stream4 逆向）**：大厅界面点"练习"→ 客户端
  在练习房界面点"开始"发出 **op 75**，body = `E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)`
  （2026-11-08 由客户端发包函数 0x00517233 逐字段钉死：`E2`房间名(空) `E2`房间密码(空) `E1(0)`=
  CFxContext 取值 `E1(1)`=玩家数 `E1×人数`=玩家 id（抓包 5 = 当前选中的 bomber）`E1(15)`=**房间类型**
  `E4(66)`=由房间类型算出的**游戏规则 id**）。服务器连发整套房间设置回应（线序 seq=187~328）：
  `[87 RoomList, 83 EnterSessionResult, 4(13B), 4(65B), 113, 100(78B), 108, 106×8,
  每槽(114,104,105,103 交错, 槽7 无 103), 198, 423]`；
  随后客户端自动跟发 297/302，再发 **op 92** 触发游戏开始 → S 回 `[111, 112 SelectMap(165B),
  100(77B), 136(178B 棋盘块)]`（线序 seq=331~340）。
- **S→C op 113 = 房间名 / 会话名同步（2026-11-08 反编译钉死，`PracticeBlobs.EN_OP113`）**：
  blob `a5 69` 不是「数值 0」，而是 `EncodeStr(房名)` 的 `E2(0)` 长度前缀（空房名）；
  完整包 `a5 18 a5 69`（前 2B = Encode2(113)）。`CSlotStage::OnPacket` 0x4F480C /
  `CPrepareStage::OnPacket` 0x52A949 / 对局阶段 0x616D59 三处 case 113 分别指向
  逐字节相同的 handler（0x4F0B07 / 0x51F86F / 0x61273B）：`sub_78FDF0`(→`sub_78F910`)
  读 [E2 长度][内容] 字符串，长度 >0x16 截到 21 字符，`sub_68872B`→`sub_44886E(this+113, str)`
  把名字赋给全局会话对象 +0x1C4 的房名字段。反证：0x005147B9 建房时把玩家输入的房名
  写进同一字段并作为 C→S op 75 第 1 字段发出；0x00506513（op 83 进房结果）从房间缓存
  取出条目的房名写入；0x004EC7F8 case 3 用空串清空并切场景。⇒ op 113 是服务端权威回显 /
  变更当前房名（可覆盖客户端输入），练习房无房名所以抓包为空串；普通房应发真实房名
  （等价结构化写法 `EncodeStr(name)`）。实现与地址链详见 `packet/Practice.kt` 注释。
- **S→C op 83 = `EnterSessionResult`「进房结果」(2026-11-08 反编译钉死；旧表名 SessionSimpleInfo 已废弃)**：客户端
  `CLobbyStage::OnPacket` 0x00510287 的 `case 'S'(0x53)` → 0x00507113，先 `Decode1` 取**结果码**
  （0=成功；1..12 = WrongPassword / CantJoinRoom / InvalidGrade / … 各弹一个对话框且**不再读任何字段**），
  成功分支才继续：`E1` flag（大廳策略 0x00516C0F 消費，存入 CFxContext+0x5A4，本區無讀取點）+
  `E2` **房号**（0x00506513 用它到 CLobbyStage 的房间缓存数组找房，**找不到整段跳过**）+
  `E1` **房间类型**（与 C→S op 75 第 6 字段同源，原样回填）+ `E1` 会话状态。
  即 body = `E1(result) E1(flag) E2(房号) E1(房型) E1(state)`，抓包 = `5a 5a a5 22 55 5a`（0,0,75,15,0）。
  已改由 `packet/EnterSessionResult.kt` 程序化生成（常量 `SESSION_ENTER_*` / `SESSION_TYPE_PRACTICE`，
  房号取 `Room.sessionId`，房型回显 op 75 请求值），`PracticeBlobs.EN_OP83` 已删除，
  `tools/gen_practice_kotlin.py` 不再生成该 blob；回归基准 `src/test/kotlin/bnbemu/netty/EnterSessionResultTest.kt`。
- **C→S op 75 是通用「创建 / 进入会话」请求，非练习房专用 (2026-11-08 普通房实包 + 反编译定论)**：
  `CSendPacket_CtorWithOp(..., 75)` 在 `reference/CA.exe.c` 共 8 处
  (269546 / 269568 / 270030 / 270840 / 271682 / 272279 / 273058 / 530200)，练习房只是参数退化的一条路径；
  `sub_517233` (0x00517233) 的字段顺序 =
  `EncodeStr(房名) EncodeStr(密码) E1(a4 模式) E1(玩家数) E1×玩家数(bomber) E1(a5 房型) E4(规则id)`；
  271986 练习房传 `(0, 15)`、272008 普通房传 `(1, a2)`、272093 走建房对话框 `(v8, v9)`，
  272279 / 530200 两处普通房路径硬编码 `E1(1) ... E1(0) E4(0)`。
  `sub_516C5A` (0x00516C5A) 把房型映射成规则索引：15 → 66、0 → 0、14 → 3、其他 → 2。
  两种实包 body（不含 opcode）：
  · 练习房 `tst.pcap` sq=79 (12B) = `A5 69 A5 69 5A 5B 5F 55 96 CA 53 D7`（两个空串 / a4=0 / 房型 15 / 规则 66）
  · 普通房 (23B) = `A5 62` + 11B 加密房名 + `A5 69 5B 5B 5A 5A 96 CA 53 95`
    （房名 = `Come quick~` / 密码空 / a4=1 / 玩家数 1 / bomber 0 / 房型 0 / 规则 0）
  ⚠️ **房名与密码是 `CSendPacket::EncodeStr` 的 3 度混淆串 (0x75DAF0)，不是明文**：
  4B 组 BE 读 ^0x96CA5395 按 LE 写回，余 2B ^0xA569，余 1B ^0x5A；服务端侧对应
  `PKT.kt` 的 `DecodeCipherStr` / `EncodeCipherStr`（此前没有任何 handler 使用）。
  旧实现用 `Decode2` 只读长度不消费内容，普通房到达时后面 6 个字段全部错位（房型读成 229，
  又被 `takeIf { 1..31 }` 退回 15），等于把所有普通房当练习房回。
  **已落地 (1~3)**：`handler/CreateSessionReq.kt` 改用 `DecodeCipherStr` 读两个串、
  按玩家数循环读 bomber id、按 `a4` 记录模式并把实际房型原样回显到 op 83；
  请求快照存入 `Room.sessionMode / sessionType / ruleId / name / password / localBombers`；
  `packet/EnterSessionResult.kt` 新增 `SESSION_TYPE_NORMAL = 0` 并去掉 0 的越界退回；
  `PKT.kt` 的 `DecodeCipherStrAt` 对声明长度做 clamp（错位包不再越界断连）。
  回归：`PracticeSetPlayerTest` 用上述 23B 实包断言房名 / 模式 / 房型 / 规则 / 完整消费，
  `EnterSessionResultTest` 断言房型 0 原样回显；`mvn test` 全绿。
  **待办 (第 4 项，需另抓普通房 S→C 包)**：普通房走 `CPrepareStage`（与练习房 `CSlotStage`
  读法不同，见 op 104 的 3B 变体），回应链 `[113, 100(78B), 108, 106×8, 每槽(114,104,105,103), …]`
  目前仍两路共用；槽位也仍是「1 真人 + 7 AI」，分叉点已标在 handler 的 `isPractice` 处。
  另外 op 87 `RoomList` 的房名是「E4 名长 + dword 对齐 + ssdEncode」的容器（见 `RoomList.kt`
  的 f1 长度前缀警告），**不适用** `EncodeCipherStr` 的 E2 长度格式，不能在这里直接套用；
  op 113 回显真实房名时该用明文 `EncodeStr` 还是 `EncodeCipherStr` 也需随普通房抓包一并验证。
