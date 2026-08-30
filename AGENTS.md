#

尝试适配泡泡堂老版本服务端模拟器的项目。已有私服地址66.135.19.7

游戏路径：F:\Games\PopTag
主程序：CA.exe

## 工具
- E:\Softwares\Wireshark\tshark.exe

---

# 协议逆向结论（2026-08，Locale=EN）

> 依据 cap/*.pcap 三个抓包（start / login / enter-channel，抓包对象即 66.135.19.7），
> 188 个加密包按下列格式全部通过 CRC8 校验（CRC8 当 oracle 逆向）。
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
- TCP **47611**：Nexon passport 登录协议（UTF-16 字符串，抓包中出现 `nmco_session`），
  客户端先在此换取票据再连 3838；模拟器完全未实现，**是当前最大缺口**

## opcode（已补全进 EN_0_recv/send.yaml）
- 可信命名（抓包时序验证）：C→S 2 AskFirstInfo、7 OnConnEstablished、15 ChannelsStateReq、
  64 UserSimpleInfoReq、81 JoinSessionReq；S→C 1 MultiServerInit、2 FirstInfoInit、11 ChannelsExtraInfo、
  13 ChannelsState、14 ChannelsGameState、20 ResEstimateRTT、81 JoinSessionResult、200 FxStockInit、210 EnableShop
- 已实现绑定（原名 Unk_* 占位）：C→S 0 AskFirstSession（首连打开）、55 Unk_55（登录后自动跟发，
  回频道列表）、11 EnterChannelReq（进频道）；S→C 193 FirstSessionNotify、67 FirstInfoTail、
  51 MultiServerTail、4 AskFirstInfoResult、12 Unk12（名字为推测者见 packet/ 注释）
- 尚无 handler/packet（yaml 里 Unk_* 占位）：
  C→S 39、78、274、276、281、293、297、302、310；
  S→C 48、60、65、66、72、78、86、87、97、122、198、204、212、227、247、254、261、274、293、302、310、413、423
- 注意：**带下划线的 Kotlin 函数名无法注册发送包**（OpcodeManager 的 filteredName 会
  substringBefore('_') 截断），新 Packet 类名不能含下划线；handler 顶层 val 名可含下划线（精确匹配字段名）
- 关键观察：op 87 服务器周期推送（66 次，44~56B）；op 86（12 次）；
  op 122 体为 `[Encode1(0)][Encode4(5000)]` 疑似 5 秒定时器；op 4 是 AskFirstInfo 的回应（65B / 13B 两种，
  13B 变体出现在频道内查询应答中，尚未实现；65B 体内含 4B 会话串，与 op 297/254 同串）；
  op 48 是 op 7 后的空体回应；op 210 EnableShop 实为 24KB 商城目录、op 65 ≈50KB、op 60 ≈46KB（疑似地图/资源数据）
- 与旧 TW_5 表冲突处：进频道请求真实是 **op 11**（旧表 EnterChannelStageReq=9）；
  C 发 2 后服务器回 **op 4**（旧表把 FirstInfoInit 当 2 的回应；实际 1861B 的 op 2 是服务器主动推送）

## SSD 资料块编码（op1/op2/op51/op11/op13/op12 内嵌子块，2026-08-30 逆向）
- 结构：`Encode1Bool(true) + Encode4(len) + len 字节资料块`（op13/op12 无开头的 Encode1Bool；
  尾部不足 4 字节的部分为 Encode2(0)/Encode1(0) 原样）
- 资料块变换：**每 dword 以 BE 读取、XOR 0x96CA5395、按 LE 写回**（即 Encode4 的字节序交换形式）
- op1（46B）解码 = multisvr.ssd 缓存体：`18290000 | 00 00 01 06 00 00 00 "PopTag" |
  0b 00 00 00 "Main Server" | 00 00 00 00 | 01 00 | IPv4(BE) | port(u16 LE)`；
  op51（32B）解码 = filterstr.ssd（"Channel 1"/"Normal"）；op2（1854B）为字串表
- **op11（211B）解码 = 频道列表**：5 频道 "PopTag-Forever" / "Ranked" / "Baby-Channel" /
  "Town" / "SquareCompat"，每频道带人数上限 u32、当前人数 u8 与一个 IPv4(BE)；
  op13（38B）= 各频道开关/状态；op12（21B）几乎全 0
- 已改写为 127.0.0.1 的 IP 位置：`packet/MultiServerInit.kt` EN_MULTISVR_BLOB（op1, 1 处）、
  `packet/ChannelsExtraInfo.kt` EN_CHANNELS_EXTRA_BLOB（op11, 5 处）——**改服务器地址需同步**
  `tools/patch_ip.py`（客户端二进制侧）与这两处（模拟器下发侧）
- 客户端行为：op1 到达后写 multisvr.ssd 并取得 login 服务器地址；**缺失即报"无法连接登录服务器"**

## 真实流程（按抓包时序，逐字节细节查 decoded_dump.txt）
1. 连 47611 passport 换票据（协议未逆向）
2. 连 3838 → 收 banner → 客户端会话的首个连接发 op 0（22B）→ S 推送启动序列 **[193, 2 FirstInfoInit 1861B, 67, 1 MultiServerInit 54B, 51]**（seq 43/46/49/52/55）
3. 登录连接：C 发 op 2（2B opcode + 40B 票据数据）→ S 回 **op 4**（65B）；
   紧跟 C 自动发 op 55 → S 推 **[11 ChannelsExtraInfo 218B, 13 ChannelsState 44B, 12 27B]**（频道列表）；
   用户选频道后 C 发 op 11（4B 频道 hash）+ 297 + 39 → S 推 **[11, 13]**，随后进入大厅期
4. C 发 op 7 OnConnEstablished（含加密角色名、hash）→ S 回 op 48 + 13 + 14（+97）
5. 进频道：C 发 op 11（4B 频道 hash）→ S 回 [11 ChannelsExtraInfo 218B, 13 44B, 12 27B, 11, 13]
6. 大厅期 S 周期推 op 87 / 86 / 122；进房 81 JoinSessionReq → 81 JoinSessionResult（131B / 101B 两种）
- 版本线索：banner 内两个 Encode2(18)（`00 12 00 12`），若为版本字段则 EN 客户端可能是 **v18**（未验证；EN_VER 现为 0 占位）

## 测试资产（改协议层后必跑）
- `tools/replay_test.py` + `replay_first_cs.bin` / `replay_login_cs.bin` / `replay_enter_cs.bin`：
  把真实客户端原始字节流回放给模拟器的回归测试（banner 逐字节比对 + C→S 全接受 + S→C CRC8 全过；
  first 用例断言推送 ops=[193,2,67,1,51]，login 用例断言 [4,11,13,12,11,13]）
- `tools/extract_first_push.py`：从 start.pcap 提取首连推送明文（cap/firstconn_s2c_op*.bin）、
  改写 op1 内 IP 并生成 Kotlin hex 常量与 replay_first_cs.bin
- `tools/extract_login_flow.py`：从 login.pcap 提取登录连接的 op4/11/13/12 明文
  （cap/login_s2c_op*.bin）、改写 op11 内 5 处 IP 并生成 Kotlin hex 常量
- `tools/dump_flow.py <pcap> <stream>`：按时间戳交错输出指定 TCP 流的 C→S/S→C 解码包（对齐请求→回应）
- `tools/decode_final.py`：pcap 全量解码器；`cap/decoded_dump.txt`：逐包明文（opcode 标签已对齐 EN_0 表）

## 构建 / 运行约定
- JDK 21
- Maven 无 wrapper：`~/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`，
  `JAVA_HOME=/c/Program Files/Java/jdk21.0.12_9`
- **必须在项目根目录启动**（Config.yaml / EN_0_*.yaml 按 CWD 加载）：
  `java -cp "target/classes;<classpath>" pingu.MainKt`，classpath 用
  `mvn dependency:build-classpath -Dmdep.outputFile=cp.txt` 生成
- Locale=EN：EN_0_*.yaml = 破解适配的真实协议；TW_5_*.yaml = 原作者原始实现（帧格式互不兼容，切回 TW 即回到旧协议）
- 原作者的 isTW 业务分支（LoginResult / ChannelsExtraInfo / NettyServer UDP / OnConnEstablished）仍在，
  EN 下不触发；实现 EN 业务逻辑时用 isEN 另写，勿改回 isTW
