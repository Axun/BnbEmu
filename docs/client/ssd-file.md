### 1. 什么是 `.ssd` 文件？

在 Nexon 早期游戏（如《泡泡堂 BnB / PopTag》、《跑跑卡丁车》等）体系中，**`.ssd`** 是 **Server Synchronized Data**（服务端同步数据 / 缓存）文件的后缀名。

#### (1) 设计目的与生命周期
`.ssd` 文件是客户端本地的**动态业务配置与运营数据缓存**：
- 像**服务器 IP 列表**、**敏感词过滤词库**、**多语言字串表**、**频道/房间列表**、**活动动态地图**、**大厅轮播广告图**等，都不硬编码在客户端主程序中。
- **冷启动与版本比对**：
  1. 客户端启动或连接服务器时，先上报本地已有 `.ssd` 文件的版本号（例如通过 op 55 `StringTableRevisionReq` 或握手 Banner）。
  2. 如果本地版本与服务端当前版本**一致**，客户端直接从本地读盘载入，避免每次登录都重复传输几十甚至上百 KB 的网络数据。
  3. 如果本地文件**不存在或版本落后**，服务端通过数据包下发最新的数据流，客户端收到后自动写盘更新本地 `.ssd` 文件。

#### (2) 文件物理格式（2026-11-04 按游戏目录实测校正）
所有 `.ssd` 文件均由固定的 **8 字节文件头 + 数据块** 构成：
- `0x00 ~ 0x03`（4 字节）：魔数 ASCII **`ssi1`**（`73 73 69 31`，即 *Server Synchronized Information v1*）。
- `0x04 ~ 0x07`（4 字节）：整包校验和，**不是 CRC32，而是 Adler-32 变体**（`a`/`b` 初值均为 0；标准 Adler-32 的 `a` 初值是 1）：
  `a=(a+byte)%65521; b=(b+a)%65521; checksum=(b<<16)|a`，计算范围为 8 字节头之后的全部字节。
  等价写法 `zlib.adler32(body) - (len(body)<<16) - 1`。
  `multisvr / filterstr / csysstr / channel2_000 / fxdynmapinfo / packagemap.ini` 六个文件逐一复算通过。
- `0x08 ~ 结尾`：SSD 数据块，**本地落盘为明文或 zlib 压缩流，并未加密**：
  `[0x18][u24 LE payloadLen][u8 0][u8 compress][u32 LE rawSize 仅 compress=1][payload]`，
  其中 `payloadLen = 数据块总长 - 5`，`compress=1` 时 payload 为 `78 da` 开头的 zlib 流，`rawSize` 为解压后字节数。
  ⚠ 旧版本此处写作「dword 字节序逆转 + 异或 `0x96CA5395` 加密」有误：那是**服务端网络下发** SSD 资料块时
  的编码（见 `tools/extract_first_push.py` 的 `ssd_decode` / AGENTS.md 的 SSD 资料块章节），
  客户端解码后才写盘；本地 `.ssd` 与之不是同一种字节形态，混用会解出乱码。

#### (3) `fxdynmapinfo.ssd` 解析结论（`CDynamicMapMan` 动态地图表）
实例：`F:\Games\PopTag\fxdynmapinfo.ssd`，4130 B，校验和 `0x71EC01A6`，`compress=1`，
4112 B zlib 流 → 24111 B 明文，**342 条记录**。只读解析/导出工具与产物：
`tools/parse_fxdynmapinfo.py`、`cap/fxdynmapinfo.csv`、`cap/fxdynmapinfo_plain.bin`。
**自 2026-11-04 起，`src/main/resources/data/fxdynmapinfo.json` 是唯一事实源**（见第 (5) 节）；
`cap/fxdynmapinfo.json` 是早期含 `index/offset/tail` 派生列的全字段导出，仅作对照，不参与生成。

解压后布局：`[u16 LE count=342] + count × Record`，每条记录（字符串均为 `u32 LE 长度 + ASCII 字节`）：

| 字段 | 类型 | 含义 |
| :--- | :--- | :--- |
| `id` | u32 LE | 运营下发记录的稀疏主键（1..514、全表唯一），**客户端选图不用它**；协议 `mapId` 是 `flag==0` 记录按文件顺序的 0-based 下标，见下方「`mapId` 来源」 |
| `flag` | u8 | **0 = 进入 `CDynamicMapMan` 选图数组**；非 0 是同一地图的其它模式/活动变体。客户端按文件顺序取 `flag==0` 的 193 条，其下标即 `mapId`（见下方「`mapId` 来源」） |
| `name` / `display` | str ×2 | 地图资源名（对应 `Map/<name>.bin`）/ UI 显示名 |
| `subType` + `themeId` | u8 ×2 | `themeId` 与 `group` 强相关：Camp=120、Factory=150、Forest=160、Patrit=220、PangLand=100、Village=240、Wriggle=242、MonPen=252、Monster=254、Cemetery=130、Rabbit=245、RPG_*=104…；`subType` 普通图为 0，随机图/特殊图非 0 |
| `group` / `stage` | str ×2 | 主题分组（27 种）/ 舞台玩法分类（21 种，含 `npc`、`boss`、`none`、`M_P_NormalStage`、`M_P_BossStage`） |
| `tail` | 16 B | 固定尾块：`u8 标志`、`u8 0/1`、`ff ff ff`、4B 位域、`u16 LE sort_order 0..187`（**即协议 `themeId`**）、`u8 类别 1..5`、`u32 0` |

表内还含 `HotRandom`、`WriggleRandom`、`MonPenRandom` 等**虚拟随机池条目**（`stage=none`、显示顺序 0）。
与 `Map/*.bin` 交叉核对：本地 173 个地图文件中 168 个在表内；表中 258 个唯一资源名有 90 个本地无文件
（远程/活动/过期），本地多出的 5 个是 `Monster37/38/39`、`Square01`、`shopmap01`。
解析器对 342 条记录逐条做序列化往返比对，全部字节归位、无剩余字节。

**网络下发链路（2026-11-04 核对）**：真实服务端在进频道握手时推 **S→C op 210**（`cap/decoded_dump.txt:152`，sq=121，包长 24123 B），
包体 = `Encode2(210)` + `Encode4(24117)` + `EncodeBuffer(SSD 块)`；该 SSD 块是**未压缩**的同构块（`compress=0`），
解码后 24111 B 记录表与本地 `fxdynmapinfo.ssd` 解压明文**逐字节一致**。
⚠ 早期笔记把末字节 `0x5A` 记作「额外的 `Encode1(0)` 字段」有误：SSD 块长 24117 B = 4×6029+1，
`EncodeBuffer` 对尾部不足 4B 的余数字节做 `XOR 0x5A`，原始明文末字节 `0x00` 因此编码为 `0x5A`，客户端解码后还原；
它仍是记录表自身的最后一字节，不是包尾独立字段。客户端收到后自行加
`ssi1` 头 + Adler-32 校验和 + zlib 压缩写盘，因此本地文件是「服务端推送内容」的压缩缓存，不是客户端自带资源。
当前 BnbEmu **已在下发，且自 2026-11-04 第三步起由数据实时构造**（不再是抓包常量回放）：
`data/FxdynMapInfo.kt` 从 `src/main/resources/data/fxdynmapinfo.json` 读取 342 条记录，序列化成
24111 B 明文 → 未压缩 SSD 块 24117 B → `EncodeBuffer` 异或 → 24121 B 包体；
`packet/DynamicMapInfo.kt`（原 `EnableShop.kt`）只剩一次 `EncodeBuffer(FxdynMapInfo.packetBlob)`，24 KB hex 常量已删除。
框架按 4 份 `*_send.yaml` 的 `DynamicMapInfo` key（EN/TW/JP=210、CN=209；2026-11-04 由旧名 `EnableShop` 正名）补 2 B opcode。
⚠ 抓包解码产物 `cap/decoded_dump.txt` / `tst_decoded.txt` / `tst_flow4.txt` 里该包仍标注 `EnableShop`，
是当时 yaml key 的历史快照，属原始数据，未改动。
发送点仍是 `handler/JoinSessionReq.kt:53`（C→S op 81 触发的进频道握手推送链）和
`handler/OnConnEstablished.kt:79`（`inGameServer` 分支）。Kotlin 输出与抓包 24123 B / 24121 B **逐字节一致**
（`FxdynMapInfoPacketTest` 回归）。**版本号比对 = S→C op 212 首字段**：op 212 的 `Encode4` 是
`fxdynmapinfo` 数据校验戳，客户端 `sub_532337`（`CA.exe.c:258183`）写入 `CEffectMan+0x24`，
`sub_531F79`（`:258038`）以 `sub_78BB50(stream) == this+9` 决定是否解析记录表；
`sub_78BB50` = `COSmartStream +0x14`，由 `sub_78BB60`（`:747998`）对 SSD 块
`[compress=0][记录表明文]` 算 Adler-32 变体（a/b 初值 0）。抓包基准 `0x36CEE587`
对应旧 342 条 / 24111 B 明文，已用 `cap/fxdynmapinfo_plain.bin` 复算一致。
BnbEmu 现由 `data/FxdynMapInfo.checksum` 从**同一份 `plain`** 实时计算（`ssdStreamChecksum`），
op 210 内容变化会自动同步到 op 212，不会再脱节；当前 1573 条 / 108601 B 对应 `0xF041AFE3`。
Banner 的 `ssdVersion=55`（`packet/ConnEstablished.kt:21`）管的是 `fxdynimginfo.ssd`，
C→S op 55 `StringTableRevisionReq` 上报的是字串表/任务字串/过滤词库三个 revision，均不含 `fxdynmapinfo`。

**`mapId` / `themeId` 来源（2026-11-04 反汇编 CA.exe 确认）**：客户端 `CDynamicMapMan` 加载本表后，只把
`flag==0` 的 **193 条**按文件顺序放进选图数组；**该数组的 0-based 下标就是 C→S op 85 / op 92 的 `mapId`**。
证据：`0x52F56C` 先拿 mapId 与容器 size 比较（越界返回 0），再 `operator[](mapId)` 取元素 `+0x68` 的 u16 作为
`themeId`；`0x51C60F`（op 85）的 mapId 来自函数参数 `[ebp+8]`、`0x531A09`（op 92）来自
`[CDynamicMapMan+0x40]`（当前选中项），随机池由 `0x53149B` 遍历下标解析。
所以表中的 `id`（176=`Monster18`）与协议 `mapId`（176=`Cemetery04`）必然对不上：两者是不同命名空间，只有 `name` 能关联。
`themeId` 数值等于记录的 `sort_order`（Cemetery03=28、Cemetery04=29，与抓包 op 85 的 28 / op 112 的 29 吻合）。

验证：`flag==0` 过滤后下标 145=`Patrit14`、175=`Cemetery03`、176=`Cemetery04`，正好是旧 `maps.yaml` 原有三条；
172=`Cemetery` 组虚拟随机条目（= op 92 的 `roomSelectedMapId=172`）、66=`CampRandom`（练习房按钮）、0=`HotRandom`。
2026-11-04 第二步起 **`src/main/resources/data/maps.yaml` 已删除**：`handler/room/StartGameReq.kt` 的
`loadMapIdTable()` 从 `data/FxdynMapInfo.records` 派生 193 个 `mapId → MapInfo(name, themeId)`；
第三步后 op 210 包体也由**同一份 `List<FxdynRecord>`** 序列化，两者共用内存对象，不存在双源。
JSON 是 YAML 1.2 子集，现有 kaml 可直接解析，**未新增依赖**。
读取/校验失败改为 fail-fast（旧实现只打日志并返回空表，会让所有地图静默兜底 `Cemetery03`）。
`mvn clean test` 全量通过，`testLoadMapIdTable` 覆盖 0/66/145/172/175/176、themeId 28/29 与越界 999。

**2026-11-11 收口**：这套 join 已搬进 `data/map/MapCatalog`——`MapCatalog.ref(mapId)` 给 `MapRef(name, themeId)`
（只依赖 JSON，恒有），`MapCatalog.of(mapId)` 给 `MapTemplate`（带 `.bin` 里的人数/队伍数/等级门槛/会员限制，
无 `.bin` 的虚拟条目返回 null）。`handler/room/StartGameReq.kt` 里的 `data class MapInfo`、`mapIdTable`、
`loadMapIdTable()` 已删除，handler 只调 `MapCatalog.ref(mapId)?.name`；对应测试改名 `testMapRefFollowsFlagZeroOrder`
并加断言 `MapCatalog.of(0) == null`（HotRandom 无地图文件）。

#### (4) 游戏目录下常见的 `.ssd` 清单
| 文件名 | 内部全称 / 对应类 | 作用 | 对应协议下发 |
| :--- | :--- | :--- | :--- |
| `multisvr.ssd` | `CMultiServer` | 登录服务器与各区服 IP:Port 列表 | 首连推送 S→C op 1 (`MultiServerInit`) |
| `filterstr.ssd` | 敏感词库 | 聊天与起名的屏蔽词列表 | 首连推送 S→C op 51 (`MultiServerTail`) |
| `csysstr.ssd` | 系统字串表 | 客户端界面文本与任务字串 | 首连推送 S→C op 2 (`FirstInfoInit`) |
| `fxdynmapinfo.ssd` | `CDynamicMapMan` | 动态地图配置（活动地图属性、分组） | 进频道握手 S→C op 210 无条件推送（2026-11-04 正名 `DynamicMapInfo`，旧名 `EnableShop`；内容由 JSON 实时生成） |
| `channel2_000.ssd` | 频道列表缓存 | 各频道 ID、状态与人数上限 | 进频道连线 S→C op 11 |
| **`fxdynimginfo.ssd`** | `CDynamicImgInfo` | **动态广告与运营图像信息表** | **由握手 Banner 中的 `55` 控制版本** |

#### (5) JSON 单一事实源与字节级可复现性（2026-11-04）

为把 op 210 从「抓包常量回放」改成动态生成（三步均已完成），现以
**`src/main/resources/data/fxdynmapinfo.json`（schema `bnbemu/fxdynmapinfo@1`）为唯一事实源**：
`records` 是 342 条记录的数组，**数组顺序具有协议含义**（`flag==0` 过滤后的下标即 `mapId`），
禁止排序、去重、以 `name` 或稀疏 `id` 作 key。每条 15 个字段可无歧义重建记录表全部字节；
`source` 段只记录基准指纹、`wire` 段只记录编码常量，**序列化器都不读取**，避免「把答案写进输入」。

| 文件 | 职责 |
| :--- | :--- |
| `src/main/resources/data/fxdynmapinfo.json` | 唯一事实源（86 KB，每条记录一行，便于 diff） |
| `src/main/kotlin/bnbemu/data/FxdynMapInfo.kt` | **运行时**：解析 JSON、序列化 24111 B 明文、构造 op 210 包；`records` 同时供 mapId 派生 |
| `src/main/kotlin/bnbemu/packet/DynamicMapInfo.kt` | 只写 `EncodeBuffer(FxdynMapInfo.packetBlob)`，无二进制常量 |
| `src/test/kotlin/bnbemu/netty/FxdynMapInfoPacketTest.kt` | Kotlin 输出与抓包基准的逐字节回归 |
| `tools/fxdynmapinfo_codec.py` | Python 双向编解码核心：`records ↔ 24111 B 明文 ↔ .ssd 文件 ↔ op 210 包` |
| `tools/build_fxdynmapinfo.py` | 生成 JSON + 全量验证 CLI（含 `.ssd` 的 zlib/Adler-32 复现） |
| `cap/rebuilt/` | 验证时写出的三份重建产物，供 `cmp` 人工复核 |

```bash
python tools/build_fxdynmapinfo.py                 # 生成 + 验证
python tools/build_fxdynmapinfo.py --verify-only  # 只验证，不改写 JSON
```

**验证结果（全部 PASS，退出码 0）**：只以 `records` 为输入重新生成的
`24111 B 明文` / `4130 B .ssd` / `24123 B op 210 包` / `24121 B 包体`，与
`cap/fxdynmapinfo_plain.bin`、`F:\Games\PopTag\fxdynmapinfo.ssd`、
`cap/chan_s2c_op210_24123B_0.bin`、`cap/practice_s2c_op210_24121B_0.bin` **逐字节一致**
（sha256 前 16 位依次为 `bee1dc646682ff0b` / `0a706f7216ccfa3c` / `151cc5e447e472e9` / `29cb413057e085c5`），
独立 `cmp` 复核同样通过。附带断言：`records` 双向往返、193 个派生 `mapId`、
`0/66/145/172/175/176` 六个已知编号、两个 `themeId`（28/29），
以及 `mapId` 连续无空洞（0..192）。

`.ssd` 能精确复现的关键：`zlib.compress(plain, 9)` 与客户端写盘时的压缩流逐字节相同；
Adler-32 变体按最终 body 复算（本例 `0x71EC01A6`）。

**第二步（2026-11-04 已完成）**：`maps.yaml` 已删除，`StartGameReq.kt` 改为从同一 JSON 派生
`mapId → MapInfo(name, themeId)`；`GameStartMapInfoTest` 的 `testLoadMapIdTable` 同步改写并扩充断言，
`tools/parse_fxdynmapinfo.py --maps-yaml` 与 `tools/build_fxdynmapinfo.py --maps-yaml` 两个废弃入口一并移除。
`mvn clean test` 全量通过（干净构建后 `target/classes/data/` 已无 `maps.yaml` 残留），
`python tools/build_fxdynmapinfo.py --verify-only` 全部 PASS。

**第三步（2026-11-04 已完成）**：删除 `packet/DynamicMapInfo.kt`（原 `EnableShop.kt`）的 24 KB hex 常量，新增 `data/FxdynMapInfo.kt`。
Kotlin 侧只需实现**网络包**序列化，不需要 `.ssd` 的 zlib / Adler-32 变体（那是客户端写盘时自己算的，
Python 侧 `build_fxdynmapinfo.py` 已能复现落盘文件）：
`serializePlain` → `buildBlock`（`compress=0`）→ `encodeBuffer`（4B/2B/1B 三档异或）→ `buildPacket`
（`Encode2(210)` + `Encode4(blockLen)` + payload）。`DynamicMapInfo()` 在 `isEN` 时写入
`FxdynMapInfo.packetBlob`；非 EN 分支按抓包行为仍只推一个 bool。

`records` / `packetBlob` 在进程内 lazy 缓存一次，**改 JSON 后重启即同时改变 op 210 内容与 mapId 映射**。
因为下发与编号派生同源，往中间插入 `flag==0` 记录不会再出现「表已更新、编号没跟上」的漂移
（但会影响其后所有 `mapId`，对局/房间侧若有硬编码编号仍需同步检查）。

`FxdynMapInfoPacketTest` 5 项断言：明文 24111 B、完整包 24123 B、包体 24121 B 三份 sha256 与抓包一致
（`cap/` 文件存在时额外逐字节 `assertArrayEquals`，文件未跟踪时以指纹兜底），
改一条记录后下发字节与 `mapId=176` 派生同步变化，以及改名后 opcode 绑定与四份 yaml key 同步。
`mvn clean test` 全量通过，Python 侧 `--verify-only` 全部 PASS。

**正名（2026-11-04）**：`EnableShop` → `DynamicMapInfo`。CA.exe 大厅分发器的 op 210 case
（`0x502F94`）调用 `CDynamicMapMan` 解析整包并写 `fxdynmapinfo.ssd`，与商城开关无关；
二进制内不存在 "EnableShop" 字符串，旧名是项目早期按包序误猜。改名同步了：
`packet/DynamicMapInfo.kt` 的文件名与函数名、两处 handler 的 import/调用、回归测试、
4 份 `*_send.yaml` 的 key（EN/TW/JP=210、CN=209）以及文档标签。
⚠ `OpcodeManager` 的 `filteredName` 取自编译产物
`DynamicMapInfoKt$DynamicMapInfo$$inlined$PKT$1`，即**函数名决定 opcode 绑定**；
key 不同步会静默返回 `-1` 而不是报错，因此测试里加了显式断言。函数名仍不能含下划线
（`substringBefore('_')` 会截断）。

