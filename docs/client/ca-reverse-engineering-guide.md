# CA.exe 客户端逆向速查与架构指南

> 本文档总结对 `F:\Games\PopTag\CA.exe`（32-bit x86 PE, MSVC, 含完整 RTTI）逆向所得的关键系统架构、内存结构、封包原语地址及排错方法。

---

## 1. 逆向环境与基本信息

- **镜像基址 (ImageBase)**: `0x400000`
- **主要区段**:
  - `.text`: `0x401000 ~ 0x889000`（代码段）
  - `.rdata`: `0x889000 ~ 0x97A000`（常量、虚表 VMT、RTTI CompleteObjectLocator）
  - `.data`: `0x97A000 ~ 0x994000`（全局变量、单例指针）
- **工具建议**: 直接使用 Python `pefile` + `capstone (CS_ARCH_X86, CS_MODE_32)` 自动化提取与反汇编。

---

## 2. 封包编解码核心原语与关键地址

### 2.1 基础编解码函数
| 语义 | 原始函数地址 | InPacket / OutPacket 包装地址 | 说明 |
| :--- | :--- | :--- | :--- |
| `Encode1` | `0x75D870` | `0x78D040` | `val ^ 0x5A` (1 字节) |
| `Encode2` | `0x75D880` | `0x78D060` (或直接按 BE 写入) | `val ^ 0xA569` (2 字节 BE) |
| `Encode4` | `0x75D8A0` | `0x78D060` | `val ^ 0x96CA5395` (4 字节 BE) |
| `EncodeStr`| - | `0x75D733` | 写入 2B 长度 + 字符串体 |
| `Decode1` | `0x75DA00` | `0x78F030` | 读 1 字节并推进游标 |
| `Decode2` | `0x75DA10` | `0x78F090` | 读 2 字节并推进游标 |
| `Decode4` | `0x75DA40` | `0x78F100` | 读 4 字节并推进游标 |
| `COutPacket Ctor` | `0x64EE1C` | - | `COutPacket::COutPacket(pkt, opcode)` |
| `SendPacket` | `0x6CDD48` | - | 发送封包到客户端套接字 |

### 2.2 全局分发器单例
- `CPacketDispatcher<enGameServerPacket>` (游戏服): 单例指针 `0x9F0580`
- `CPacketDispatcher<enGeneralServerPacket>` (登录/通用): 单例指针 `0x9F057C`

---

## 3. 房间与准备界面架构 (CSlotStage & Strategy)

客户端的房间界面由 **`CSlotStage`** 主导，并挂载可插拔策略类 **`CPrepareStrategy*`** 处理不同房间模式（AI 对战、教程、普通对战等）：

```
CSlotStage (0x8A13C4)
  ├── OnPacket (0x4F480C)  ──> 处理通用房间包 (104, 106, 108, 109, 110 等)
  │                              未命中则重置游标转交给 Strategy
  └── Strategy: CPrepareStrategyAIBattle (0x8A3934)
        └── OnPacket (0x52D188)  ──> 处理 AI 专属包 (103, 270, 271, 272, 407..410)
```

### 3.1 客户端状态等待锁（点击无反应排查关键）
客户端在向服务端发出请求后，会在对象内设置**等待锁**以防用户连续狂点。**如果服务端未发送正确的响应包清除锁，客户端将永久忽略后续点击**：

| 锁所在结构 | 偏移地址 | 触发设置位置 (C→S) | 解锁与刷新位置 (S→C) | 对应解锁包 |
| :--- | :--- | :--- | :--- | :--- |
| `CSlotStage` | `[esi + 0x2F64]` | `0x4F68D7`（置 `2`） | `0x4EFD27`（置 `0`） | **Opcode 106** (`PracticeSlotReady`) |
| `CPrepareStrategyAIBattle` | `[esi + 0x3250]` | `0x52B9AD`（置 `1`） | `0x52B476`（置 `0`） | **Opcode 103** (`PracticeSlot103`) |

---

## 4. 关键 Opcode 与字段结构对照表 (EN / v18)

### 4.1 C→S 客户端请求
| Opcode | 名称 / 触发场景 | 载荷结构 | 说明 |
| :--- | :--- | :--- | :--- |
| **55 (0x37)** | `StringTableRevisionReq` (字串表版本上报) | `E4(stringTableRev) + E4(questStringRev) + E4(curseFilterRev)` | 登录成功后进入选区界面时上报本地 3 份 SSD 版本 (StringTable, QuestString, CurseFilter) 并触发下发频道列表推送 |
| **88 (0x58)** | `AILevelChangeReq` (调等级) | `E1(chief) + E1(slotIdx) + E1(newLevel) + E1(flags)` | 设置 AI 难度等级 |
| **89 (0x59)** | `OpenCloseSlotReq` (踢人/开关槽) | `E1((slotIdx << 4) \| (action & 0xF))` | `action & 1 == 1`: 踢出/关闭 (`FLAG_CLOSED=1`)<br>`action & 1 == 0`: 开放/加入 AI (`FLAG_CLOSED=0`) |
| **75 (0x4B)** | `CreatePracticeSessionReq` (进练习房) | `E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)` | 进练习房 |
| **92 (0x5C)** | `StartGameReq` (游戏开始确认) | 空包 | 准备完毕触发开局 |

### 4.2 S→C 服务端响应
| Opcode | 名称 | 载荷结构 | 功能与作用 |
| :--- | :--- | :--- | :--- |
| **12 (0x0C)** | `ChannelsNotice` (选区公告与PPL广告) | `E4(len) + SSD[noticeTitle, p1..p3, pplType/urls, msgCount/msgs, tailStatus]` | 选区/频道列表界面的公告横幅 (`CFxApp`)、赞助商/广告横幅 (`CPPLManager`) 与滚动系统公告 (`CFxMessage`)，并刷新 `CSelectServerDlg` UI |
| **103 (0x67)** | `PracticeSlot103` | `E2(slotIdx) + E1Bool(isAI) + E2(level) + E2(flags)` | 设置 AI 敌人对象并解锁策略锁；`isAI=false` 时移除 AI 敌人 |
| **104 (0x68)** | `PracticeSlot104` | `E1((slotIdx << 4) \| flag)` | 槽位属性/队伍标志 |
| **105 (0x69)** | `PracticeSetPlayer` | `E1(slotIdx) + E1(bomber)` | 槽位外观角色 (5=宝宝) |
| **106 (0x6A)** | `PracticeSlotReady` | `E1((slotIdx << 4) \| state)` | 槽位开关状态 (`state & 1` 对应 `FLAG_CLOSED`)，解除槽位锁 2 |
| **114 (0x72)** | `PracticeSlot114` | `E1(slotIdx) + E1(5) + E1(5) + E1(0) + E2(0)` | 槽位装配状态 |

---

## 5. 逆向分析常用套路与技巧

1. **查找某个 C→S 操作的发包点**:
   - 搜索 `push <opcode>` 后跟 `call 0x64EE1C`（COutPacket 构造函数）。
   - 紧接着会看到一系列 `call 0x78D040` (`Encode1`) 或 `0x78D060` (`Encode4`) 写入字段。
2. **查找某个 S→C 操作的接收处理**:
   - 在 Stage 或 PacketHandler 的 `OnPacket` 中，搜索 `call 0x78F090` (`Decode2`) 获取 Opcode；
   - 随后通常是连续的 `sub eax, <const>` 或 `cmp eax, <const>` 构成的分支链。
3. **定位“点击后客户端无反应”的原因**:
   - 先看发包函数尾部设置了哪个偏移标志（如 `mov dword ptr [esi + 0xXXXX], 1`）；
   - 全局搜索该偏移 `0xXXXX`，找出哪个函数执行了 `and dword ptr [esi + 0xXXXX], 0` 或 `mov ..., 0`；
   - 向上回溯该清除函数的调用者，即可精准找到客户端所等待的响应 Opcode。
