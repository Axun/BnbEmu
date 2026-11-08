- 关键观察：**op 87 = 房间列表（真，2026-08-30 确认，非普通周期推送）**：34 个实例、11 种长度
  （40~59B，随房名长短变化；出现于登录期与大厅期）；结构
  `E2(1) + E4(f1) + E4(f2) + E4(f3) + E4(f4) + E4(名长) + 房名(NUL 补齐到 dword) + E4×3 + E1/E2(0)`；
  **房名加密与 op 72 令牌同款变换**，已解出 13 个真实房名：rd / Everyone welcome! /
  Taylor Swift is here / gogo battle / 01234567899 / vietnam / come together! / /np / all no /
  K8-k-3 / K8XNaVYLFQ / tw / 1 —— enter-channel.pcap 的 41B 实例就是 **"rd" 房**（模拟器 RoomList
  重放包，客户端 UI 显示的正是它）。f1/f2/f3/f4/tail 与 房间编号/上限/当前人数 的对应尚未钉死
  （rd 实例：f1=33, f2=7192[低16位恒 0x18], f3=61<<16, f4=119, t1=0x08000000, t2=0x04100077, t3=0）；
  下一步：在真实服务器房间界面（rd #12 1/1）抓 1 分钟 op 87，按 12/1/1 值匹配钉死字段；
  op 86（12 次，23~25B）；
  **op 122 UserDynamicInfoValue = CUserDynamicInfo 单Lucci同步；op 20 UserDynamicInfo = 本机玩家动态档案快照**；op 4 有三个变体（登录连线 65B /
  频道连线 65B 全零 / 13B 查询应答，均含或邻接 4B 会话串）；
  op 210 DynamicMapInfo为 **动态地图表**（CDynamicMapMan；由 `fxdynmapinfo.json` 实时生成，见 `docs/client/ssd-file.md`）、op 65 ShopCatalog ≈50KB（商城商品与角色卡全量目录，已结构化）、op 60 ItemDef ≈46KB（全量物品定义目录，已结构化支持动态配置与进阶角色卡启用）；
  **op 72 SessionList = 频道在线用户列表**（⚠️ 2026-08-30 与游戏界面比对，非房间列表；已结构化：`packet/SessionList.kt` 的 SessionEntry）：
  `E2(0) + E1(条目数) + N 条目`，条目 = `E2(len)+角色名令牌(4~6B) + 42B 固定栏位`；
  已解出栏位语义：number=E2 玩家**等级**（样本 326→1 递减 = 326..1 级玩家，非房间号）、
  maxCount=E4 恒 50（频道人数上限）、curCount=E4（样本 16/17/0，频道当前在线数）、
  state=E1 与 state2=E2（样本仅一玩家同为 1，是否同一标志待观察），其余恒 0；
  **令牌 = 玩家角色名的加密形式**（2026-08-30 由末条 = 抓包账号 kcworms1 的角色名 ffdf 对出：
  按 4B 一块 LE 读 ^ 0x96CA5395 按 BE 写回（SSD/Encode4 逆变换），尾部 2B **LE 读 ^0xA569 按 BE 写回**、
  尾部 1B ^0x5A，**尾部 3B = 2B 尾 + 1B 尾**（2026-08-30 由 tst.pcap 角色名 kcworm1 [7B] 对出：
  令牌 f9bd30fec81b6b = kcwo 4B块 + rm 2B尾 + 1 1B尾；初解漏 2B 尾字节序交换导致 6B 名末两字对调），
  令牌长恒等于角色名长、任意长度成立；8 条 enter-channel 样本经游戏界面人名核为
  Kuso/Lotso/Jacko/suan/Hello/Mirage/Static/ffdf 且回编码逐字节一致）；
  S→C op 81 名册回显同一批玩家令牌（细节见 JoinSessionReq.kt 注释）
  —— replay_test 对 op 72 做逐字节断言；
  **op 6 ReqConnectToSvr 是 EN 进频道的真实步骤**（2026-08-30 更正：此前误判"TW 专用、EN 抓包从未出现"，
  实际藏在 enter-channel.pcap 登录连线收尾的半截流里，decoded_dump 对其记 0 包）：C 发 op 6
  （E1(10)+E4(2)，E4 含义不明、真实抓包为 2 / live 客户端为 0）→ S 回 **op 7**
  （E1(1)+E1(10)+E4(回显)+E4(IP)+E2(3838)，已逐字节解出）→ 客户端关登录连线、新开连线到 IP:port。
  ResConnectToSvr 已注册为 S→C op 7（EN_18_send.yaml；TW 客户端仍预期 op 6，TW_5_send.yaml 未动）
- 与旧 TW_5 表冲突处：进频道请求真实是 **op 11**（旧表 EnterChannelStageReq=9）；
  C 发 2 后服务器回 **op 4**（旧表把 FirstInfoInit 当 2 的回应；实际 1861B 的 op 2 是服务器主动推送）

- **Opcode 310 & 302 逆向确凿结论 (2026-11-08)**：
  - **S→C Op 310 UserMarketItemList (个人交易市场物品列表)**:
    - 客户端处理入口：`CUserMarketStage::OnPacket` (0x00631C78)，分发至 `0x0062EA8C` -> `0x0062E1B7`。
    - 数据包解码格式：
      - `Decode2` -> count (物品条目数)
      - if (count == 0): 弹出对话框 `UMItemNotFound` (0x008B4184, "UserMarket Item Not Found")。
      - if (count > 0): 循环 `count` 次，每项结构：`Decode4(itemId) + Decode4(price/count) + DecodeStr(itemName) + Decode4(duration/expire) + Decode2(statusFlags)` (若开启特性 0x20 则追加 Decode1)。
    - 抓包中 20B (如 `enter-channel.pcap seq=64/94`) 真实内容为：`count=1, itemId=0, price=0, itemName="", duration=0, status=0`（18 字节载荷 + 2 字节 opcode），作为全 0 空条目占位下发，以防触发未找到提示。
  - **S→C Op 302 UserMarketMyItemList (个人寄售物品列表)**:
    - 客户端处理入口：`CUserMarketStage` (0x0062E02F / 0x0062F4A0)，数据结构与 Op 310 完全同构。
    - 在练习房抓包 (`practice_s2c_op302_24B_0.bin`) 中曾携带 1 条 Camo 道具记录 (`itemId=19888, price=2004, name="Camo", duration=240000, status=0`)。
  - **C→S Op 310 UserMapListReq (频道在线用户列表/地图请求)**:
    - 发送入口：`TSingleton<CUserMap>::RequestUserMap` (`0x004E02E3`, 单例位于 `0x00998414`, 虚表 `0x0089DE24`)。
    - 触发源：`CLobbyStage::InitStage` (`0x00512F6F`) 与 `CUserMap::UpdateGuildInfo` (`0x0063F1D5`)。
    - 行为：空载荷包，发包后设置等待锁 `[this + 0x2ABC] = 1`。当收到服务器 S→C Op 72 (`SessionList`) 时，分发至 `0x004E086B` 并在 `0x004E0854` 将等待锁清零恢复。
  - **C→S Op 302 UserStageSyncReq (玩家舞台/特性状态同步请求)**:
    - 发送入口：`CStage::vtable[+0xA8]` (`0x004FA1AE`) 及 `CMyPageDialog` (`0x005F1AEA`)。
    - 行为：客户端在进入 Lobby(11) / Room(12) / Square(20) 以及个人资料主页时发送，载荷为 `Encode1(flag)`。

- **S→C op 83 = `EnterSessionResult`「进房结果」(2026-11-08 反编译钉死；旧名 SessionSimpleInfo 已废弃)**：
  - 分发：`CLobbyStage::OnPacket` (0x00510287) 的 jump-switch `case 'S' (0x53)` → `0x00507113`。
    同组 82/84/85 (`case 'R'/'T'/'U'`) 也是会话操作：`0x0050BB9D` / `0x00507CC7` / `0x0050702E`。
  - `0x00507113` 流程：`[stage->vtable+0x110](0)` 清等待锁 → `Decode1` 结果码 → 跳表 `0x00507C93`：
    - `case 0` 成功 → 特性 80 检查 → 大廳策略虚表 `+0x18` → `0x00506513`
    - `case 1..12` 失败 → **只弹对话框，不再读字段**（所以失败包只需 1 字节）：
      1 WrongPassword / 2 CantJoinRoom / 3 InvalidGrade / 4 InvalidLucci / 5 InvalidGachapon /
      6 DontHaveTMItemJoin / 7 UserRejectJoinSession / 8 InvalidLevel / 9 DeferShutdown /
      10 InvalidWinRate / 11 InvalidGradeSeason / 12 HordeEvent_RecommendBuyHordeTicket
  - 成功分支的字段：
    - `E1` flag → 大廳策略虚表 `+0x18`（普通 `0x00516C0F` / 任务 `0x00515FE1` / 通关 `0x005187CA`
      都只是 `Decode1` 存进 `CFxContext+0x5A4`，**全 .text 无 +0x5A4 读取指令**；兑换 `0x005146AD`
      当 bool 用选 stage 16/17；阶梯 `0x00515080`、特殊赛 `0x00517E0E` 干脆不读）
    - `E2` **房号** → `0x00506513` 调 `0x00505123` 在 `CLobbyStage+0x6968` 的房间快取数组
      （每笔 204B，笔数 `+0x6964`，由 op 87 房间列表填充）里按 id 找房；**找不到整段跳过**
      → 房名/等级/人数不会写进 `CFxContext`，但「进房成功 + 切 stage 12」照常发生
    - `E1` **房间类型** → `0x0068626F` → `CFxContext+0x570`；与 C→S op 75（`0x00517233`）
      第 6 字段同源，抓包两边都是 15。`0x00516C5A` 把类型映射成规则索引：14→3 / 15→1 / 0→0 / 其他→2
    - `E1` 会话状态 → `0x00687393` → `CFxContext+0x1AC`（抓包 0，建房路径 `0x44FA…` 也置 0）
  - 同族包：`CNormalLobbyStrategy` 虚表 `+0x10` = `0x00516CEF` 是**带完整房信息**的变体
    （`E2` 房号 + `E4` + `E1` + `E1` + `E1`，末尾 `sub_68DC53(stage,12)` 切房间场景）；
    op 83 则是「最小版」：只给房号 + 房型 + 状态，其余从 op 87 的房快取补齐。
  - 结论：body 固定 6B = `E1(result) E1(flag) E2(房号) E1(房型) E1(state)`，
    抓包 `5a 5a a5 22 55 5a` = 0/0/75/15/0。实现见 `packet/EnterSessionResult.kt`，
    回归见 `src/test/kotlin/bnbemu/netty/EnterSessionResultTest.kt`。
