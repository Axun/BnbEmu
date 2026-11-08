# PopTag 客户端（EN 破解版）服务器地址机制逆向笔记

> 逆向日期：2026-08-30。对象：`F:\Games\PopTag`（Locale=EN 破解版客户端，原版 2010-01-26，
> CA.exe / NMCOSrv.exe / nmconew.dll / nmcogame.dll 均为破解者改过的二进制）。
> 分析手段：全目录字节扫描 + pefile/capstone 交叉引用与反汇编（无符号）。
> 结论均已落地到 `tools/patch_ip.py`（批量改地址补丁脚本）。

## 0. 一句话结论

`NMDATA\Server.cfg` **从头到尾就没被成功解析过**（纯文本，过不了 ssi3 魔数检查），它的唯一
消费者是 NMCOSrv.exe，且即便生效也只影响 33567 端口的 NMCO 会话列表。真正决定客户端连哪里的是：

| 连接 | 地址来源 | 定义位置 |
|---|---|---|
| 游戏 3838（登录/频道/房间） | CA.exe 内置默认服务器表 + multisvr.ssd 缓存 | CA.exe .rdata 补丁 6 处；multisvr.ssd 偏移 0x2E |
| passport / NMCO 47611 | nmconew.dll 硬编码（ASCII 默认槽 + UTF-16LE 地址表，实际消费 UTF-16 表） | nmconew.dll 补丁 1 处 ASCII（file 1378624）+ 8 处 UTF-16LE；nmcogame.dll 8 处 UTF-16LE |
| NMCO 会话 33567 | NMCOSrv.exe 内置表（Server.cfg 优先但格式不符即回退） | NMCOSrv.exe 补丁 2 处 |

> **2026-08-30 补遗（UTF-16LE 服务器地址表，此前漏补的原因）**：全目录字节扫描发现
> `66.135.19.7` 还以 **UTF-16LE** 形式存在于两张 NMCO 服务器地址表——nmconew.dll @1328332 起
> 8 槽、nmcogame.dll @241988 起 8 槽（槽宽 32 字节、NUL 结尾），混在 Nexon 各国 passport
> 地址（KR 218.145.45.x / JP 203.141.248.x / TH 202.80.111.x / 国服一片 / NexonTH 61.91.69.x /
> NA 208.85.111.x / RU 91.202.203.x）之间，破解者各改了 8 槽。首版 patch_ip.py 只扫 ASCII，
> 导致打过补丁后 47611 仍连远端、账号在远端校验（只能用私服注册账号登录）。两张表已加入
> `tools/patch_ip.py` 的 UTF16_TARGETS；nmcogame.dll 里另有破解者自带的 7 处 UTF-16 `127.0.0.1`
> 本地测试槽，佐证该表正是破解流水线改地址之处。

注册表（HKLM/HKCU 的 Nexon 键）没有任何 PopTag/CA 相关配置，地址不走注册表。

## 1. 运行拓扑

```
CA.exe (游戏主程序)
 ├─ 启动 NMCOSrv.exe   ← "%s\%s" + "NMCOSrv.exe"，窗口类 "Crazy Arcade"，标题 "CA Messenger Module"
 │    └─ 连接会话服务器 66.135.19.7:33567（LoadServerConfig：Server.cfg 失败→内置表）
 ├─ nmconew.dll        ← NMCO/passport 库，连 66.135.19.7:47611（UTF-16 协议，nmco_session）
 └─ 游戏连接           ← multisvr.ssd / 内置默认表 → 66.135.19.7:3838（服务端 op1 MultiServerInit 会写回缓存）
```

三个补丁过的二进制里 IP 全部是**明文 ASCII 原地替换**（原版 15 字节 IP 槽位，写入 11 字节的
`66.135.19.7` + 补 `\0`），CA.exe 无 ASLR（base 0x400000），下列 VA 即静态地址。

## 2. CA.exe（base 0x400000，raw==RVA，文件偏移 = VA - 0x400000）

### 2.1 内置默认服务器表（.rdata）

整片地区默认 IP 表位于 VA `0x8BC0C8`–`0x8BC4B0`，原版内容为各国服务器：

- 韩：211.39.154.180~182/115/116、210.208.92.71~74
- 越南（VinaGame，参见键名 `vinadomain`）：221.231.129.x/130.x、61.152.97.x/101.x、222.73.2.x、222.73.23.137
- 国服（sdo/盛大）：125.39.105.12~14、119.147.190.122~124；登录域名 `login.bnb.com.cn`（0x8BC4B0）
- 其他：109.105.134.194、203.141.248.38

破解补丁把其中 6 项替换为 `66.135.19.7`（本文件偏移，可直接 hex 编辑）：

| 文件偏移 | VA | 位置说明 |
|---:|---|---|
| 4964568 | 0x8BC0D8 | 国际列表第 2 项（前有 `CA_MultiSvr` 标签 0x8BC0BC） |
| 4965464 | 0x8BC458 | 国服列表尾部（0x8BC408~0x8BC448 为 222.73.2.184~188） |
| 4965480 | 0x8BC468 | 同上 |
| 4965496 | 0x8BC478 | 同上 |
| 4965520 | 0x8BC490 | 同上 |
| 4965536 | 0x8BC4A0 | 同上 |

替换后字符串变短、以 `\0` 填充，表项指针不受影响（各 IP 都是独立 C 字符串，被指针逐个引用）。

### 2.2 服务器列表管理器（选择逻辑）

- 入口 `0x6CD892`：参数为文件名字符串。与 `"multisvr.ssd"`（0x8B8BFC）/`"multisvrn.ssd"`
  （0x8BC4FC）比较：匹配则从文件加载列表（0x6CD446 / 0x6CD5C8）；空名则构造 name=`"Default"`
  （0x8BC50C）的默认条目。
- 地区选择链 `0x6CD7CE`–`0x6CD843`（`call 0x6C04F0` 为标志位测试）：

  | 标志位 | 选中的登录地址 |
  |---|---|
  | 0x4 | `login.bnb.com.cn`（未补丁，仅 CN locale 会命中） |
  | 0x8 | 210.208.92.71 |
  | 0x40 | **66.135.19.7**（补丁） |
  | 0x2 | 203.141.248.38 |
  | 0x10 | **66.135.19.7**（补丁） |
  | 0x80 | 109.105.134.194 |
  | 兜底 | **66.135.19.7**（补丁） |

  EN 环境命中补丁分支 → 必然指向私服。
- **默认端口 3838 硬编码**：`mov dword ptr [esi+0x68], 0xEFE` @ `0x6CD7BF`、`mov edi, 0xEFE`
  @ `0x6CC5A9`。内置表本身不含端口。
- 相关字符串：`"Default Server List"` 0x8BC4E8、`"This is built-in default Server."` 0x8BC4C4、
  `"Cannot open a file called as 'multisvr.ssd' with a write right."` 0x8BC518（写缓存失败提示）。
- 缓存写入路径在 `0x6CDA60` 一带（把服务器下发的列表写回 multisvr.ssd）。

### 2.3 启动 NMCOSrv 与区域键

- `0x683FDC`：`GetModuleFileNameA` 取目录 → `"%s\%s"` 拼出 `NMCOSrv.exe` → 启动（"CA Messenger Module"）。
- `0x68465D` 起读自身配置键：`loginip`、`vinadomain`（值与 `"svrHCM"`/`"svrHN"` 比较，写
  `[edi+0x578]` = 1/2），随后调 `0x6CD892` 加载服务器列表。

## 3. NMCOSrv.exe（base 0x400000）与 Server.cfg 不生效的根因

### 3.1 LoadServerConfig（0x40FF20）

```
LoadServerConfig(this, flag):
  if flag == 0:
      if LoadSsi3File("NMDATA\Server.cfg", &stream):   # 0x43ADA0
          首个 u32（流读取器 0x439730）必须 == 0x68
          否则 → 跳到 From Source
          成功 → 循环读记录（每条 0x14=20 字节，0x439850），端口 u16（0x4396D0）
                 日志 "From File - LoadServerConfig : %d"（0x46BDA8）
                 return
  From Source（内置表，"From Source - LoadServerConfig : %d - %d" 0x46BD78）:
      for i in 0..0:                                    # 仅 1 条
          ip   = dword [i*8 + 0x46C414]   → 指针 0x46C41C → "66.135.19.7"
          port = word  [i*8 + 0x46C418]   → 0x831F = 33567
```

### 3.2 ssi3 文件格式（0x43ADA0，Server.cfg 必须满足才被读取）

```
偏移 0x00: "ssi3"          # 魔数，ReadFile 4 字节逐一比对，失败即放弃
偏移 0x04: <u32>           # 校验值，须等于 0x43DCC0(0, body, body_size) 的返回值
偏移 0x08: body            # 长度 = 文件大小 - 8；经 0x439A80 装入流对象
body 首个 u32 == 0x68      # 0x439730 逐 u32 读出，读取时流内校验状态
                           # [obj+0x14] = (旧值 << 8) ^ 0xCDFF8135
随后每条记录 20 字节（IP + 端口等）
```

现在的 `NMDATA\Server.cfg` 是纯文本 `1\t66.135.19.7\t33567\n`（20 字节）——
**第一关 `"ssi3"` 魔数就失败**，永远走 From Source 内置表。而内置表本身也被补丁成了同一个
`66.135.19.7:33567`（文件偏移 443412 处 `1C C4 46 00 | 1F 83` = {指针→0x46C41C, 33567}），
所以表象是「IP 是对的，但怎么改都不生效」。

### 3.3 其他补丁点与杂项

- 补丁 IP 共 2 处：file 441152（VA 0x46BB40，`CNMClient::OnInviteUserPacket` 旁，
  'local'/'remote' 连接类型处理）与 file 443420（VA 0x46C41C，From-Source 表字符串本体）。
- `NMDATA\Game.cfg`（0x410200 读，文件不存在，跳过）。
- `Connect to session server failed! (%s:%d)`（0x442300）——33567 连不上只影响好友/信使。
- `szClientDLL19`~`szClientDLL27`：注册表值名（客户端 DLL 列表，与本课题无关）。
- `NMData\%016I64x.cfg`：按用户 ID 的配置缓存（0x442580）。

## 4. nmconew.dll（base 0x10000000）——passport 47611

- 补丁 IP 仅 1 处：file 1378624 = VA 0x10150940（ASCII，`\0` 结尾，两侧是
  `"Cookie: NPP=%s"`、`"IsWow64Process"`）。
- 唯一引用：`0x100EC948  push 0x10150940 → call dword ptr [0x10142380]（inet_addr）→
  0x1012A416（组 sockaddr）`，即**硬编码默认会话服务器地址**。
- 端口 47611（0xB9FB）以 `push 0xB9FB` 硬编码约 90 处——NMCO 默认会话端口，无独立配置。
- 协议特征（对应 pcap 47611 流量）：UTF-16 字符串、`nmco_session`、`Cookie: NPP=%s`
  （Nexon passport 票据）。模拟器尚未实现此端口（AGENTS.md 所列最大缺口）；客户端侧库就是
  本 DLL，端口/地址/调用点均已定位。

## 5. multisvr.ssd（游戏根目录，54 字节，ssi1 封装）

```
0x00 73 73 69 31              "ssi1" 魔数
0x04 aa 08 ba b3              未知（时间戳/随机；csysstr.ssd 等同格式文件亦然）
0x08 18 29 00 00              未知
0x0C 00 00 01 06 00 00 00     ┐ u32 长度前缀字符串（推断）
0x13 "PopTag"                 ┘
0x19 0b 00 00 00 "Main Server"  服务器名
0x28 00 00 00 00 01 00        条目元数据（推断）
0x2E 42 87 13 07              ★ IPv4 大端 = 66.135.19.7（补丁点）
0x32 fe 0e                    端口 u16 小端 = 3838
0x34 00 00                    尾部
```

来源：登录后服务器 **op1 MultiServerInit** 下发列表 → 客户端写回（0x6CDA60 一带）；
下次启动优先读缓存，读不到才用 CA.exe 内置表。**注意：模拟器下发什么，缓存就会被覆盖成什么**，
所以长期方案是在模拟器 op1 里给正确地址，改这个文件只是临时手段（本补丁脚本会一并改掉它）。

> 2026-08-30 更新：op1 MultiServerInit 的 54B 载荷结构已逆向（`Encode1Bool(true) + Encode4(46) +
> 46B SSD 资料块`，资料块变换 = 每 dword BE 读 ^ 0x96CA5395 后 LE 写回，解码即 multisvr.ssd 缓存体，
> IPv4 在解码偏移 38–42）。模拟器侧已在 `packet/MultiServerInit.kt` 的 EN_MULTISVR_BLOB 内把
> IP 硬编码为 127.0.0.1:3838——**改地址要同时改这里和 tools/patch_ip.py 覆盖的客户端补丁点**，
> 重新生成用 `tools/extract_first_push.py --ip <ip>`。详见 AGENTS.md「SSD 资料块编码」一节。

## 6. NMDATA\Server.cfg 现状

内容 `1\t66.135.19.7\t33567\n`（20 字节纯文本）。如第 3 节所述：格式不符从未被读取；
即使按 ssi3 重新生成（需复刻 0x43DCC0 校验算法），影响面也只有 NMCOSrv 的 33567 会话列表。
处理策略：**忽略即可**（补丁脚本为一致性起见会把文本里的 IP 一并替换）。

## 7. tools/patch_ip.py 使用说明

```
python tools/patch_ip.py                    # 把 F:\Games\PopTag 里所有 66.135.19.7 → 127.0.0.1
python tools/patch_ip.py --ip 192.168.1.10  # 指定其他地址（二进制槽位要求长度 ≤ 11）
python tools/patch_ip.py --game-dir D:\xx   # 指定游戏目录
python tools/patch_ip.py --dry-run          # 只预览将要做的修改
python tools/patch_ip.py --scan-only        # 全目录扫描旧 IP 残留（跳过 *.ipbak）
python tools/patch_ip.py --restore          # 从 *.ipbak 备份还原全部文件
```

- 首次打补丁前自动生成 `<file>.ipbak` 原始备份（已存在则不覆盖，永远保存最初版本）。
- 二进制 ASCII 槽位按「等长 + `\0` 填充」替换，不移动任何字节、不破坏指针/对齐。
- multisvr.ssd 在偏移 0x2E 按网络序替换 4 字节 IP（端口 3838 不动）。
- 该文件也会被服务器 op1 覆盖；若私服下发列表仍是旧 IP，重新登录后需再跑一次本脚本，
  或直接在模拟器 EN_0 的 MultiServerInit 里配置新地址。

## 8. 补丁点速查表（改地址 = 改这些）

| 文件 | 偏移 | 类型 | 内容 |
|---|---:|---|---|
| CA.exe | 4964568 / 4965464 / 4965480 / 4965496 / 4965520 / 4965536 | ASCII+`\0` | 内置默认服务器表 ×6 |
| NMCOSrv.exe | 441152 / 443420 | ASCII+`\0` | invite 回退 / From-Source 表（443412 处指针与端口同步生效，无需另改） |
| nmconew.dll | 1378624 | ASCII+`\0` | passport 47611 默认地址 |
| multisvr.ssd | 0x2E | u32 网络序 | 服务器列表缓存（会被 op1 覆盖） |
| NMDATA\Server.cfg | 全文 | 文本 | 实际不生效，仅一致性 |

还原：删除/还原 `.ipbak`，或 `python tools/patch_ip.py --restore`。
