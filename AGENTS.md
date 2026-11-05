# BnBEmu

尝试适配泡泡堂老版本服务端模拟器的项目。

游戏路径：F:\Games\PopTag
主程序：CA.exe

## 工具
- E:\Softwares\Wireshark\tshark.exe

---

# 协议逆向结论（2026-08，Locale=EN）

> 依据 cap/*.pcap 三个抓包（start / login / enter-channel，抓包对象即 66.135.19.7），
> 188 个加密包按下列格式全部通过 CRC8 校验（CRC8 当 oracle 逆向）；另有 enter-channel.pcap
> 登录连线收尾 5 帧半截流（跨 pcap 截断，2026-08-30 经 brute_c2s/brute_s2c 暴力对齐 seq 补解，
> 内含 op 6→op 7 关键交互，见"关键观察"）。
> 权威参照：`tools/decode_final.py`（解码器 + 协议注释）、`cap/decoded_dump.txt`（逐包明文）。
> 加密原语与项目实现完全一致，当年对不上的只有帧封装和 opcode 表，均已修正并落地到代码。

## 帧格式（已在 Codec.kt EN 分支实现）
- seq 两方向各自独立计数，初始 40，步进 3，**每连接重置**（实测不是进程级全局；曾疑似 seq=70 起连，实为抓包截断的残流）
- S→C 握手 banner（占 seq=40）：`[0x00][len:2 BE 明文][payload 明文]`，无 hc、无 CRC、无加密；
  banner 20 字节在三次抓包中逐字节一致，已照抄进 `ConnEstablished`（isEN 分支）：
  `00 00 12 00 12 00 00 00 00 01 00 00 00 00 00 00 37 00 00 00`
- S→C 加密（seq 从 43 起，banner 占用 40）：`[hc][0x00][len:4 BE ^ 0x96CA5395][payload][CRC8]`，hc = 231^(102+seq)
- C→S 加密（seq 从 40 起）：`[hc][uid:2][len^0x69:1][payload][CRC8]`，hc = 231^(192+seq)；
  uid 每连接恒定、由客户端带入（观测 13/198/230，LE16 ^ 0xA569），服务端目前只跳过不用
- payload 两方向同构：`[Encode2(opcode)][字段...]`，opcode = BE16 ^ 0xA569
- 字段混淆：Encode1 ^0x5A / Encode2 ^0xA569 / Encode4 ^0x96CA5395；
  **字符串长度恒为 Encode2（2 字节）**——虽然是 degree-3 帧，串长不是 4 字节
  （证据：op7 角色名前缀 `a5 6d` = Encode2(4)）
- 密码学原语与项目实现一致，勿动：simpleStreamEncrypt3(key=seq)（常量 0x53351D9C / 0x63CAACE3，逐包重启）；
  CRC8 poly 0x07，key = seq&0xFF，算在**明文** payload 上

## 端口拓扑（tshark SYN 实测）
- 游戏 TCP **3838**：登录、频道、房间全部业务都在这一个端口；项目原 3838/4848/4849 多端口分服布局与真实不符
- TCP **47611**：Nexon passport 登录协议（nmco_session，UTF-16），客户端先在此验证账号密码换取票据再连 3838。2026-10-07 已彻底逆向 nmconew.dll 破译；协议帧封装、0x18 容器流、PCBC 加密算法及业务字段等所有具体实现细节已全部记录在源码注释中，详见 `src/main/kotlin/bnbemu/netty/PassportServer.kt`。
  ⚠️ 客户端二进制侧的 47611 地址在 nmconew.dll/nmcogame.dll 的 **UTF-16LE 表**（各 8 槽），patch_ip.py 已补齐该编码的替换（此前只补 ASCII 导致打过补丁仍连远端 passport）。

## opcode（已补全进 EN_18_recv/send.yaml）
- 可信命名（抓包时序验证）：C→S 2 AskFirstInfo、7 OnConnEstablished、15 ChannelsStateReq、
  64 UserSimpleInfoReq、81 JoinSessionReq；S→C 1 MultiServerInit、2 FirstInfoInit、7 ResConnectToSvr、
  11 ChannelsExtraInfo、13 ChannelsState、14 ChannelsGameState、20 ResEstimateRTT、
  81 JoinSessionResult、200 FxStockInit、210 DynamicMapInfo
- 已实现绑定（原名 Unk_* 占位）：C→S 0 AskFirstSession（首连打开）、
  55 StringTableRevisionReq（登录后上报本地 3 份 SSD 版本号 [StringTable, QuestString, CurseFilter]，触发回频道列表）、
  11 EnterChannelReq（进频道）、15 ChannelsStateReq（频道状态轮询，回 op 13）、
  64 UserSimpleInfoReq（空消费，回应在 StageInitReq 链内）、297/39/302/310/293（空消费/部分消费，
  整套回应由 StageInitReq 触发）、78 StageInitReq（批次收齐触发点，回整套进频道回应链）、
  81 JoinSessionReq（进频道自动空包，⚠️ 非"进房"；2026-08-31 起回应=整条**进频道握手推送**，
  客户端发 81 后会等这条推送才继续发 274/281/276，缺它卡"正在连接"对话框，见 JoinSessionReq.kt）、
  274（13B×2）/281（空）/276（空）（握手后续，空消费，无逐包回应）；
  S→C 193 FirstSessionNotify、67 FirstInfoTail、51 MultiServerTail、4 AskFirstInfoResult（登录连线 65B）
  / SessionInfoResult（频道连线 65B 全零变体）/ UserSimpleInfoResult（13B 变体，三种同类名映射同 op 4；
  前两者已结构化为 `packet/AskFirstInfoResult.kt` 的 `UserInfo` 类，userId=7215/会话串
  f0ae35f3 等账号值暂用抓包常量 CAPTURE_USER_ID/SESSION_STRING）、
  12 ChannelsNotice（**公告与PPL广告信息**，已结构化可动态生成）、48 PermissionInfo（**玩家权限设定与功能开关**，空体或 E4(id)+16×E1(flag) 列表，CPermissionMan）、97 ChangeStage（**切换游戏阶段**，E2(11) 大厅 / E2(20) 广场，CMainSystem::SetStage，触发客户端自动发 op 78 StageInitReq）、
  7 ResConnectToSvr（op 6 ReqConnectToSvr 的回应，见下方关键观察）、
  122 UserDynamicInfo（**用户动态数据同步**，如 Lucci 金币同步，详见 `packet/UserDynamicInfo.kt` ）、423（op 7 回应后的推送，原样重放）、87 RoomList（**房间列表**，已结构化可动态生成，见关键观察）、
  310/302（进频道批次回应，E2(1)+E4(0)×2+E2(0)+E4(0)+E2(0)，
  各发两次）、254 UserMapInfo（**频道在线用户地图/占位同步**，CUserMap）、227 MyPageInfo（**个人资料摘要与商城版本**，CMyPageDialog）、72 SessionList（**频道在线用户列表**，已结构化可动态
  生成，见关键观察）、
  293(E1(0))/78 QuestConfigTable（任务配置表，E4(count)+条目列表，空为 E4(0)）、60 ItemDef（全量物品定义表，已结构化类似 ShopCatalog，支持动态配置与进阶角色卡）、
  81 JoinSessionResult（**大厅名册**，已结构化 `packet/JoinSessionResult.kt` 的 `RosterEntry`，
  `E4(人数)+N×[E1槽位+E4账号数值+E2名长+令牌]`，账号数值=passport 0x2E 同值）、
  413 UserInventoryInfo（**玩家背包全量槽位初始化**，614B 50槽，CUserInventoryMan）/ 198 / 200 FxStockInit / 274 UserItemUpdate×2（**道具槽位更新**，CUserItemMan）/ 212 FxConfig（**特效与角色可用性配置**，CFxContext）/ 210 DynamicMapInfo / 65 ShopCatalog(50KB) / 204 / 66 / 14(9B 变体) / 20 / 261 ShopVersion（**商城目录版本校验与开关**，CShopItemMan，不一致触发客户端发 276）/ 247
  （进频道握手推送，多为原样重放；其中 210 已改由 `src/main/resources/data/fxdynmapinfo.json` 实时生成，顺序与触发见 JoinSessionReq.kt）
- 尚无 handler/packet（yaml 里 Unk_* 占位）：
  S→C 86（大厅期周期推送，⚠️ 非"进房后"）
- **进练习房（op 75，2026-09-01 已实现，tst.pcap stream4 逆向）**：大厅界面点"练习"→ 客户端
  在练习房界面点"开始"发出 **op 75**，body = `E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)`
  （E1(5)=bomber，E4(66)=练习房地图 id，语义待钉死）。服务器连发整套房间设置回应（线序 seq=187~328）：
  `[87 RoomList, 83 SessionSimpleInfo, 4(13B), 4(65B), 113, 100(78B), 108, 106×8,
  每槽(114,104,105,103 交错, 槽7 无 103), 198, 423]`；
  随后客户端自动跟发 297/302，再发 **op 92** 触发游戏开始 → S 回 `[111, 112 SelectMap(165B),
  100(77B), 136(178B 棋盘块)]`（线序 seq=331~340）。
- 大厅中参见 `docs/agents-notes-lobby.md`
- 房间中参见 `docs/agents-notes-room.md`
- 对局中参见 `docs/agents-notes-battle.md`
- 实现见 `handler/CreatePracticeSessionReq.kt`（op 75，⚠️ 原误绑 op 86）、`handler/Unk_92.kt`（游戏开始）、
  `handler/room/BomberActionAckReq.kt`（C→S op 172 角色动作切换确认空包）、`packet/Practice*.kt` / `packet/room/MapEventSync.kt`；
  工具 `tools/extract_op75.py`（提取回放+blob）、`tools/gen_practice_kotlin.py`（生成 blob 常量）；
  基准 cap/practice_s2c_op*.bin。op 4 的 13B/65B 变体与进频道一致（body 已核对 = chan 基准）。
- **个人名片与玩家详细资料界面（My Page / op 301, op 73, op 66, op 74, op 273, op 288 SweetieInfoReq）**：参见 `UserInfoReq.kt` + `UserRestrictReq.kt` + `SweetieInfoReq.kt`。
- **玩家换装/装备穿戴变更同步请求（C→S op 273 **：参见ChangeEquipReq.kt
- 注意：**带下划线的 Kotlin 函数名无法注册发送包**（OpcodeManager 的 filteredName 会
  substringBefore('_') 截断），新 Packet 类名不能含下划线；handler 顶层 val 名可含下划线（精确匹配字段名）
- **进入游戏商城流程（C→S op 315）**：参见EnterShopReq.kt
- **大厅聊天 C→S （op 18）**：参见ChatMsg.kt

## SSD 资料块编码（op1/op2/op51/op11/op13/op12 内嵌子块，2026-08-30/2026-09-10 逆向）
- 结构：`Encode1Bool(true) + Encode4(len) + len 字节资料块`（op13/op12 无开头的 Encode1Bool）；
  资料块变换：**每 dword 以 BE 读取、XOR 0x96CA5395、按 LE 写回**（即 Encode4 的字节序交换形式）
- 块头规律（op1/op2/op11/op12/op13 五块吻合）：`[0x18][(块长-5)&0xFF][u32 0]`
- **op11 = 频道列表、op13 = 各频道开关/状态、op12 = 公告与PPL广告信息，均已结构化**：频道表 `EN_CHANNELS` 定义在
  `packet/ChannelsExtraInfo.kt`（`ChannelEntry`，op13 用其 `state`/`enabled` 栏，
  生成逻辑在 `ChannelsState.kt`；条目逐字节结构、续读标记等细节见两文件顶部注释；
  重编码与抓包逐字节一致，replay_test login 用例断言）。5 频道
  PopTag-Forever / Ranked / Baby-Channel / Town / SquareCompat，Town/SquareCompat 为
  大地图频道、并非被关闭；改频道清单两包同步生效
- op1 = multisvr.ssd 缓存体（解码布局见 MultiServerInit.kt 注释）；op51 = filterstr.ssd；
  op2 = 字串表；**op12 = ChannelsNotice 公告与广告块 已结构化在 `packet/ChannelsNotice.kt`
- 已改写为 127.0.0.1 的 IP 位置：`packet/MultiServerInit.kt` EN_MULTISVR_BLOB（op1）、
  `packet/ChannelsExtraInfo.kt` ChannelEntry.ip（op11）——**改服务器地址需同步**
  `tools/patch_ip.py`（客户端二进制侧，含 nmconew.dll/nmcogame.dll 的 UTF-16LE 表各 8 槽）
- 客户端行为：op1 到达后写 multisvr.ssd 并取得 login 服务器地址；**缺失即报"无法连接登录服务器"**

## 真实流程（按抓包时序，逐字节细节查 decoded_dump.txt）
1. 连 47611 passport：**0x000B 登录换会话 → 换线 0x002D 查询角色**（协议实现见 `netty/PassportServer.kt`）；随后带 36B 票据连 3838
2. 连 3838 → 收 banner → 客户端会话的首个连接发 op 0（22B）→ S 推送启动序列 **[193, 2 FirstInfoInit 1861B, 67, 1 MultiServerInit 54B, 51]**（seq 43/46/49/52/55）
3. 登录连接：C 发 op 2（2B opcode + 40B 票据数据）→ S 回 **op 4**（65B）；
   紧跟 C 自动发 op 55 StringTableRevisionReq → S 推 **[11 ChannelsExtraInfo 218B, 13 ChannelsState 44B, 12 27B]**（频道列表）；
   用户选频道后 C 发 op 11（4B 频道 hash）+ 297 + 39 → S 推 **[11, 13]**
4. 客户端取进频道连线地址：C 发 **op 6 ReqConnectToSvr**（E1(10)+E4(2)）→ S 回 **op 7 ResConnectToSvr**
   （E1(1)+E1(10)+E4(2)+E4(IP)+E2(3838)；期间夹有 3 个 op 86 周期推送）→ 客户端关闭登录连接（FIN），
   新开连线到 IP:3838（60246 → 60248）
5. （新连线上）C 发 op 7 OnConnEstablished（EN 36B：E1(1) + [E2(4)+会话串] + E4(639) + E4(0) + 8B 原样 +
   频道hash×2 + E1(1)；前段与 TW 共用 userCount/name/userId 解析）→ S 回
   **[48 空体, 13, 14, 97, 122 UserDynamicInfo, 87, 423]**（线序 seq=43~61）；
   随后 C 跟发 297 / 302 / 310 / 64 / 293 / 78 批次 → 批次收齐（78 最后）后 S 按线序回
   **[310, 302, 254, 72 用户列表, 293, 78, 4(13B), 4(65B), 227, 60 46KB, 310, 302]**（seq=64~97，
   用户列表据此显示）→ 进入大厅期
   ⚠️ 线序 = seq 顺序；dump_flow.py 按时间戳排序会因 TCP 合包失真（如 122 实际紧跟 97，却显示在 60 之后）
6. 进频道：C 发 op 11（4B 频道 hash）→ S 回 [11 ChannelsExtraInfo 218B, 13 44B, 12 27B, 11, 13]
7. 进频道握手（2026-08-31 时序对齐钉死，已实现）：C 收齐 72 用户列表后自动发 **op 81 空包** →
   S 跟发整条**进频道握手推送** [310, 302,] 413, 198, 200 FxStockInit, 274×2, 122 UserDynamicInfo, 212,
   210 DynamicMapInfo, 87 RoomList, 65 50KB, 204, 66, 14(9B 变体), 20, 261, 247, 423, 81 名册, 72
   （分三波下发：客户端**等推送才继续发** 274/274/281 → 等 → 276，缺推送客户端卡"正在连接"对话框）；
   之后大厅期 S 周期推 op 81 名册 / 72 / 122 UserDynamicInfo / 87 / 86（86 尚未实现）
- 版本线索：banner 内两个 Encode2(18)（`00 12 00 12`），若为版本字段则 EN 客户端可能是 **v18**（未验证；EN_VER 现为 0 占位）

## 测试资产（改协议层后必跑）
- `tools/replay_test.py` + `replay_first_cs.bin` / `replay_login_cs.bin` / `replay_enter_cs.bin` /
  `replay_connect_cs.bin`：
  把真实客户端原始字节流回放给模拟器的回归测试（banner 逐字节比对 + C→S 全接受 + S→C CRC8 全过；
  first 用例断言推送 ops=[193,2,67,1,51]，login 用例断言 [4,11,13,12,11,13,13,13]
  （尾部 13×2 = op 15 轮询回应）及逐字节 op 4/op 11（基准 login_s2c_op11_ip127.bin = 抓包 IP 改写版）/ op 13，
  enter 用例断言含 [48,13,14,97,122,87,423] 与整套批次回应
  [310,302,254,72,293,78,4×2,227,60,310,302] 及**进频道握手推送** [413,198,200,274×2,122,212,
  210,87,65,204,66,14,20,261,247,423,81 名册,72]（与真实线序逐包一致），expect_all 按
  出现顺序逐字节断言全部 op 4（13B + 65B）/ op 274（两变体）/ op 14（10B + 9B 变体）；
  op 81 名册基准 = chan_s2c_op81_101B_0.bin（2026-08-31 从 pcap 提取）；
  connect 用例断言 [7]——op 6 ReqConnectToSvr 必须回 **op 7**，回 6 客户端会忽略、频道点击无反应；
  该 bin 为真实 op 6 载荷（E1(10)+E4(2)，原帧 seq=70/uid=198）以 ssc_enc 重封装至 seq=40 的单帧）；
  practice 用例（replay_practice_cs.bin = op7@40 + op75@43 + op92@46）断言整套练习房回应链
  [87,83,4×2,113,100,108,106×8,每槽114/104/105/103,198,423] + 游戏开始 [111,112,100,136]，
  并逐字节断言 op 83/113/108/112/136/198/423（基准 cap/practice_s2c_op*.bin，含 2B opcode）；
  ⚠️ 不期待 op 172（真实服务器有，但模拟器不周期广播，见 MapEventSync 说明）
- `tools/test_ai_sync.py`：局内水泡闭环回归（启动模拟器后跑）。断言**不再**出现 op 172 周期广播，
  并验证 C→S op 106 → S→C op 139 (SetBomberEvent) 一问一答、op 139 载荷与 tst.pcap stream4 抓包逐字节一致
- `tools/rev_op172.py`：op 172 逆向复现脚本（`--capture` 解码抓包 13 样本），输出分发器映射、
  处理器字段读取顺序、事件分发/激活链与相关 RTTI 类名
- `tools/test_room_select_bomber.py`：房间内切换角色回归（启动模拟器后跑）。用 replay_practice_cs.bin
  推进到房间阶段后发 C→S op 87（`E2(87) E1(slot) E1(bomber)`），断言换回 S→C op 105 且载荷为
  `E1(slot) E1(bomber)`（两个不同槽位各验一次，确认非硬编码）
- `tools/test_bomb_explosion.py`：水泡状态机回归（启动模拟器后跑）。C→S op 140 放泡 → S→C op 352
  (BombIgnite) → ~2.5s → S→C op 353 (SetBombState, state=0 爆炸消除)
- `tools/test_chat.py`：大厅聊天**发送方向**回归（启动模拟器后跑）。回放 replay_practice_cs.bin 进大厅，
  发 C→S op 18（文字 "asf"，令牌化 `D6 08 3C`），断言服务端能正确解码出明文 "asf"
  且不误判队伍聊天（chatType=1）。⚠️ 不校验 S→C 显示，该 opcode 尚未确定（见上文）。
- `tools/test_bomber_die.py`：对局中角色死亡与局内操作包回归（启动模拟器后跑）。模拟客户端在对局中发送
  C→S op 112 (BomberActionReq) 与 C→S op 154 (BoxItemSpawnReport action=5 角色死亡)，断言服务端不越界、
  成功广播 S→C op 159 (BomberDie)、S→C op 169 (InitGameResult) 与 S→C op 150 (GameResult) 结算对局并弹出结算画面。
- `tools/test_game_win.py`：**「杀光全部敌人 → 对局结束」回归**（启动模拟器后跑）。默认场景走真实路径：
  连发 C→S op 112 action=0x60（实测样本 `3A A5 39 A7 6E`），断言 ~1.5s 后下发 169/150、op 150 winnerTeam=0、
  `--legacy-die154` 保留老的逐槽 action=5 路径。 对应单元测试 `GameOverTest.kt`（20 项）。
- `tools/extract_op75.py`：从 tst.pcap stream4 提取练习房流程的 C→S 完整流/裁剪回放
  （op7/op75/op92 帧，op75/op92 以 ssc_enc 重封装到连续 seq）+ S→C 各 op 完整 payload
  （cap/practice_s2c_op*.bin，含 2B opcode，replay_test 基准）；`tools/gen_practice_kotlin.py`：
  由上述 payload（去 2B opcode）生成 `packet/PracticeBlobs.kt` 的 blob 常量
- `tools/extract_first_push.py`：从 start.pcap 提取首连推送明文（cap/firstconn_s2c_op*.bin）、
  改写 op1 内 IP 并生成 Kotlin hex 常量与 replay_first_cs.bin
- `tools/extract_login_flow.py`：从 login.pcap 提取登录连接的 op4/11/13/12 明文
  （cap/login_s2c_op*.bin）、改写 op11 内 5 处 IP 并生成 Kotlin hex 常量
  （op11 已结构化，该基准现仅作 replay_test 的 IP 改写版断言源 login_s2c_op11_ip127.bin）
- `tools/extract_channel_flow.py`：从 enter-channel.pcap stream1 按 TCP 线序提取进频道整套 S→C 回应
  （cap/chan_s2c_op*.bin），Unk423/Unk254/Unk227/ItemDef/SessionInfoResult 的
  Kotlin blob 常量源（blob = 载荷去 2B opcode 前缀，已验证重编码逐字节一致）；
  op 87/op 72 载荷现为 RoomList/SessionList 结构化编码的逐字节断言基准
- `tools/dump_flow.py <pcap> <stream>`：按时间戳交错输出指定 TCP 流的 C→S/S→C 解码包（对齐请求→回应）
- `tools/extract_passport.py <pcap>...`：按 TCP 流提取 47611 passport 原始帧到 cap/passport_*.bin
  （带 hex/UTF-16 预览；tshark conv 表的"字节数"含以太网头，每条流实际就是一帧请求+一帧回应）
- `tools/decode_final.py`：pcap 全量解码器；`cap/decoded_dump.txt`：逐包明文（opcode 标签已对齐 EN_0 表）。
  注意：跨 pcap 的连接尾巴是半截流，dump 会记 0 包——用 `brute_s2c`/`brute_c2s` 单帧暴力对齐 seq 解
  （**ssc 进完整 seq、CRC8 key 取 seq&0xFF，seq>255 时两者不同**）；`ssc_enc` 为 ssc_dec 的逆变换，
  可把真实载荷重封装到任意 seq 生成 replay 帧
- `tools/replay_passport.py`：passport 桩回归测试（启动模拟器后跑；回放 login.pcap 真实 C→S 帧，
  断言回应与真实成功帧逐字节一致，另覆盖另一账号/tst 失败现场/半截流恢复）

## 构建 / 运行约定
- JDK 21
- 启动命令：`mvn compile exec:java`  **必须在项目根目录启动**（Config.yaml / EN_18_*.yaml 按 CWD 加载）。
- JAVA_HOME: `C:\Program Files\Java\jdk21.0.12_9`
- Locale=EN：EN_18_*.yaml = 破解适配的真实协议；TW_5_*.yaml = 老版本其他区域客户端实现，仅供参考
- 新加的逻辑一律按EN区域客户端处理，不用判断区域。
