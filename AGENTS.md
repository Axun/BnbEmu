#

尝试适配泡泡堂老版本服务端模拟器的项目。已有私服地址66.135.19.7

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
- TCP **47611**：Nexon passport 登录协议（nmco_session，UTF-16），客户端先在此验证账号密码换取票据再连 3838。
  **2026-08-30 已实现桩**（`netty/PassportServer.kt`，isEN 时随 NettyServer.start() 绑定）：
  逐字节回放 login.pcap 的真实成功回应，任何账号密码都能过认证。请求 blob 为 NMCO 私有加密
  （非游戏协议的 Encode/ssc 家族，固定密钥流、暴力尝试未破），桩不解密，因此**所有账号在游戏内
  都会得到角色名 ffdf**（抓包账号 kcworms1 的角色）；真实服务器的 0x0C/0x2E 两步 4B 会话值本就
  互不关联，桩回放无交叉校验风险。"NGP"+账号 身份串是否被客户端严格校验未考证——用抓包账号
  kcworms1 测试与真实服务器逐字节等价，换其他账号若登录卡住优先怀疑此处（需逆向 nmconew.dll 解密）
  ⚠️ 客户端二进制侧的 47611 地址在 nmconew.dll/nmcogame.dll 的 **UTF-16LE 表**（各 8 槽），
  patch_ip.py 已补齐该编码的替换（此前只补 ASCII 导致打过补丁仍连远端 passport）

## passport (47611) 协议（2026-08-30 逆向，工具 extract_passport.py）
- 每命令独立一条 TCP 连接，一问一答即由客户端 FIN；帧 = `[len:u16 BE][cmd:u16 BE][payload: len 字节]`，
  total = len + 4
- C→S **0x000B 登录**（加密凭据 blob，长度随账号 UTF-16 长度变化：kcworm1=72B / kcworms1=74B）
  → S→C **0x000C**：成功 = 4B会话值 + UTF-16LE "nmco_session" + UTF-16LE "NGP"+账号（86B/84B）；
  失败 = 40B 短帧（tst s0 即一次密码打错的现场）
- C→S **0x002D 会话查询**（加密 blob，42B/44B）→ S→C **0x002E**：4B会话值 + u32(账号数值 7215/7307, LE)
  + UTF-16LE 角色名(u16 前缀) + `01 01 00 00` + u32(5000, LE)（135B/141B）
- 加密 blob（帧内 `[tot-20][4B 头][密文][4B trailer]`）：4B 头 = pcap 级常数 u16（59f4/5b32，疑似日期，
  同 pcap 内所有帧共享）+ 逐请求变化 u16；密文段同账号跨 pcap 仍共享字节（e2b0b435 恒在段首），
  同账号不同密码的两次尝试仅在部分 2 字节单元相异 —— 固定密钥流、无 nonce；不是 ssc/XOR 家族
- 3838 的 op 2 票据（36B）与 passport 回应 4B 会话值的关系未考证——模拟器不验证票据，无影响

## opcode（已补全进 EN_0_recv/send.yaml）
- 可信命名（抓包时序验证）：C→S 2 AskFirstInfo、7 OnConnEstablished、15 ChannelsStateReq、
  64 UserSimpleInfoReq、81 JoinSessionReq；S→C 1 MultiServerInit、2 FirstInfoInit、7 ResConnectToSvr、
  11 ChannelsExtraInfo、13 ChannelsState、14 ChannelsGameState、20 ResEstimateRTT、
  81 JoinSessionResult、200 FxStockInit、210 EnableShop
- 已实现绑定（原名 Unk_* 占位）：C→S 0 AskFirstSession（首连打开）、55 Unk_55（登录后自动跟发，
  回频道列表）、11 EnterChannelReq（进频道）、15 ChannelsStateReq（频道状态轮询，回 op 13）、
  64 UserSimpleInfoReq（空消费，回应在 Unk_78 链内）、297/39/302/310/293（空消费/部分消费，
  整套回应由 Unk_78 触发）、78 Unk_78（批次收齐触发点，回整套进频道回应链）、
  81 JoinSessionReq（进频道自动空包，空消费不回包，⚠️ 非"进房"，见 JoinSessionReq.kt 注释）；
  S→C 193 FirstSessionNotify、67 FirstInfoTail、51 MultiServerTail、4 AskFirstInfoResult（登录连线 65B）
  / SessionInfoResult（频道连线 65B 全零变体）/ UserSimpleInfoResult（13B 变体，三种同类名映射同 op 4；
  前两者已结构化为 `packet/AskFirstInfoResult.kt` 的 `UserInfo` 类，userId=7215/会话串
  f0ae35f3 等账号值暂用抓包常量 CAPTURE_USER_ID/SESSION_STRING）、
  12 Unk12、48 Unk48（op 7 后的空体回应）、97 Unk97（E2(11)，与 op 14 内 ChannelID 同值，推测频道标识）、
  7 ResConnectToSvr（op 6 ReqConnectToSvr 的回应，见下方关键观察）、
  122/423（op 7 回应后的推送，原样重放）、87 RoomList（**房间列表**，已结构化可动态生成，见关键观察）、
  310/302（进频道批次回应，E2(1)+E4(0)×2+E2(0)+E4(0)+E2(0)，
  各发两次）、254/227（含 4B 会话串的块，原样重放）、72 SessionList（**频道在线用户列表**，已结构化可动态
  生成，见关键观察）、
  293/78（E1(0)/E4(0) 小回应）、60（46KB 资源块，原样重放）
- 尚无 handler/packet（yaml 里 Unk_* 占位）：
  C→S 274、276、281（进频道自动批次续包，与 op 81 同批，⚠️ 非"进房"）；
  S→C 65、66、86、198、204、212、247、261、274、413（大厅期周期推送，⚠️ 非"进房后"）
- 注意：**带下划线的 Kotlin 函数名无法注册发送包**（OpcodeManager 的 filteredName 会
  substringBefore('_') 截断），新 Packet 类名不能含下划线；handler 顶层 val 名可含下划线（精确匹配字段名）
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
  op 122 体为 `[Encode1(0)][Encode4(5000)]` 疑似 5 秒定时器；op 4 有三个变体（登录连线 65B /
  频道连线 65B 全零 / 13B 查询应答，均含或邻接 4B 会话串）；
  op 210 EnableShop 实为 24KB 商城目录、op 65 ≈50KB、op 60 ≈46KB（资源块，已原样重放）；
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
  ResConnectToSvr 已注册为 S→C op 7（EN_0_send.yaml；TW 客户端仍预期 op 6，TW_5_send.yaml 未动）
- 与旧 TW_5 表冲突处：进频道请求真实是 **op 11**（旧表 EnterChannelStageReq=9）；
  C 发 2 后服务器回 **op 4**（旧表把 FirstInfoInit 当 2 的回应；实际 1861B 的 op 2 是服务器主动推送）

## SSD 资料块编码（op1/op2/op51/op11/op13/op12 内嵌子块，2026-08-30 逆向）
- 结构：`Encode1Bool(true) + Encode4(len) + len 字节资料块`（op13/op12 无开头的 Encode1Bool）；
  资料块变换：**每 dword 以 BE 读取、XOR 0x96CA5395、按 LE 写回**（即 Encode4 的字节序交换形式）
- 块头规律（op1/op2/op11/op12/op13 五块吻合）：`[0x18][(块长-5)&0xFF][u32 0][条目数]`
  （条目数 op11/12/13 为 u16 LE，op1 为 u8）
- **op11 = 频道列表、op13 = 各频道开关/状态，均已结构化**：频道表 `EN_CHANNELS` 定义在
  `packet/ChannelsExtraInfo.kt`（`ChannelEntry`，op13 用其 `state`/`enabled` 栏，
  生成逻辑在 `ChannelsState.kt`；条目逐字节结构、续读标记等细节见两文件顶部注释；
  重编码与抓包逐字节一致，replay_test login 用例断言）。5 频道
  PopTag-Forever / Ranked / Baby-Channel / Town / SquareCompat，Town/SquareCompat 为
  大地图频道、并非被关闭；改频道清单两包同步生效
- op1 = multisvr.ssd 缓存体（解码布局见 MultiServerInit.kt 注释）；op51 = filterstr.ssd；
  op2 = 字串表；**op12 = 空块**（条目数 0 + 12B 全零 + E1(0) 尾，全部抓包仅 login sq=52 一个样本，
  细节见 Unk12.kt 注释）
- 已改写为 127.0.0.1 的 IP 位置：`packet/MultiServerInit.kt` EN_MULTISVR_BLOB（op1）、
  `packet/ChannelsExtraInfo.kt` ChannelEntry.ip（op11）——**改服务器地址需同步**
  `tools/patch_ip.py`（客户端二进制侧，含 nmconew.dll/nmcogame.dll 的 UTF-16LE 表各 8 槽）
- 客户端行为：op1 到达后写 multisvr.ssd 并取得 login 服务器地址；**缺失即报"无法连接登录服务器"**

## 真实流程（按抓包时序，逐字节细节查 decoded_dump.txt）
1. 连 47611 passport：**0x000B 登录换会话 → 换线 0x002D 查询角色**（协议见上"passport (47611) 协议"节，
   桩已实现）；随后带 36B 票据连 3838
2. 连 3838 → 收 banner → 客户端会话的首个连接发 op 0（22B）→ S 推送启动序列 **[193, 2 FirstInfoInit 1861B, 67, 1 MultiServerInit 54B, 51]**（seq 43/46/49/52/55）
3. 登录连接：C 发 op 2（2B opcode + 40B 票据数据）→ S 回 **op 4**（65B）；
   紧跟 C 自动发 op 55 → S 推 **[11 ChannelsExtraInfo 218B, 13 ChannelsState 44B, 12 27B]**（频道列表）；
   用户选频道后 C 发 op 11（4B 频道 hash）+ 297 + 39 → S 推 **[11, 13]**
4. 客户端取进频道连线地址：C 发 **op 6 ReqConnectToSvr**（E1(10)+E4(2)）→ S 回 **op 7 ResConnectToSvr**
   （E1(1)+E1(10)+E4(2)+E4(IP)+E2(3838)；期间夹有 3 个 op 86 周期推送）→ 客户端关闭登录连接（FIN），
   新开连线到 IP:3838（60246 → 60248）
5. （新连线上）C 发 op 7 OnConnEstablished（EN 36B：E1(1) + [E2(4)+会话串] + E4(639) + E4(0) + 8B 原样 +
   频道hash×2 + E1(1)；前段与 TW 共用 userCount/name/userId 解析）→ S 回
   **[48 空体, 13, 14, 97, 122, 87, 423]**（线序 seq=43~61）；
   随后 C 跟发 297 / 302 / 310 / 64 / 293 / 78 批次 → 批次收齐（78 最后）后 S 按线序回
   **[310, 302, 254, 72 用户列表, 293, 78, 4(13B), 4(65B), 227, 60 46KB, 310, 302]**（seq=64~97，
   用户列表据此显示）→ 进入大厅期
   ⚠️ 线序 = seq 顺序；dump_flow.py 按时间戳排序会因 TCP 合包失真（如 122 实际紧跟 97，却显示在 60 之后）
6. 进频道：C 发 op 11（4B 频道 hash）→ S 回 [11 ChannelsExtraInfo 218B, 13 44B, 12 27B, 11, 13]
7. 进频道后 C 自动跟发批次 [274, 81 空包, 281, 276]（无用户操作也会发，⚠️ 非进房）；大厅期
   S 周期推 op 87 / 86 / 122、op 81 名册（⚠️ 非 81 请求的回应）及 [413, 198, 200 FxStockInit,
   274×2, 212, 210 EnableShop, 87, 65, 204, 66, 14, 20, 261, 247, 423]（S→C 81 名册与 413 等
   回应尚未实现，为下一缺口）
- S→C op 81 JoinSessionResult 名册（结构与样本已解出，细节见 JoinSessionReq.kt 注释；尚未实现）
- 版本线索：banner 内两个 Encode2(18)（`00 12 00 12`），若为版本字段则 EN 客户端可能是 **v18**（未验证；EN_VER 现为 0 占位）

## 测试资产（改协议层后必跑）
- `tools/replay_test.py` + `replay_first_cs.bin` / `replay_login_cs.bin` / `replay_enter_cs.bin` /
  `replay_connect_cs.bin`：
  把真实客户端原始字节流回放给模拟器的回归测试（banner 逐字节比对 + C→S 全接受 + S→C CRC8 全过；
  first 用例断言推送 ops=[193,2,67,1,51]，login 用例断言 [4,11,13,12,11,13,13,13]
  （尾部 13×2 = op 15 轮询回应）及逐字节 op 4/op 11（基准 login_s2c_op11_ip127.bin = 抓包 IP 改写版）/ op 13，
  enter 用例断言含 [48,13,14,97,122,87,423] 与整套批次回应
  [310,302,254,72,293,78,4×2,227,60,310,302]（与真实线序逐包一致），expect_all 按
  出现顺序逐字节断言全部 op 4（13B + 65B 两变体）；
  connect 用例断言 [7]——op 6 ReqConnectToSvr 必须回 **op 7**，回 6 客户端会忽略、频道点击无反应；
  该 bin 为真实 op 6 载荷（E1(10)+E4(2)，原帧 seq=70/uid=198）以 ssc_enc 重封装至 seq=40 的单帧）
- `tools/extract_first_push.py`：从 start.pcap 提取首连推送明文（cap/firstconn_s2c_op*.bin）、
  改写 op1 内 IP 并生成 Kotlin hex 常量与 replay_first_cs.bin
- `tools/extract_login_flow.py`：从 login.pcap 提取登录连接的 op4/11/13/12 明文
  （cap/login_s2c_op*.bin）、改写 op11 内 5 处 IP 并生成 Kotlin hex 常量
  （op11 已结构化，该基准现仅作 replay_test 的 IP 改写版断言源 login_s2c_op11_ip127.bin）
- `tools/extract_channel_flow.py`：从 enter-channel.pcap stream1 按 TCP 线序提取进频道整套 S→C 回应
  （cap/chan_s2c_op*.bin），Unk423/Unk254/Unk227/Unk60/SessionInfoResult 的
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
- 启动命令：`mvn compile exec:java`  **必须在项目根目录启动**（Config.yaml / EN_0_*.yaml 按 CWD 加载）。
- JAVA_HOME: `C:\Program Files\Java\jdk21.0.12_9`
- Locale=EN：EN_0_*.yaml = 破解适配的真实协议；TW_5_*.yaml = 原作者原始实现（帧格式互不兼容，切回 TW 即回到旧协议）
- 原作者的 isTW 业务分支（LoginResult / ChannelsExtraInfo / NettyServer UDP / OnConnEstablished）仍在，
  EN 下不触发；实现 EN 业务逻辑时用 isEN 另写，勿改回 isTW
