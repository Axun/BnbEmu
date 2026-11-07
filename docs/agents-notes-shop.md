- **进入游戏商城流程（C→S op 315）**：参见EnterShopReq.kt
- **商城购买（C→S op 267 / S→C op 202）**：参见 `handler/shop/BuyItemReq.kt` + `packet/shop/BuyItemResult.kt`。
  请求体 = `E4(prodId, 即 op65 目录的商品号) + E4(arg) + E1(flag)`（实测点 "Bomb 655"：
  `A1 A4 FC 9A | 96 CA 53 95 | 5A` → prodId=930000655）；发完客户端置等待锁 `[shop+0xC0]=1`。
  ⚠️ 结果包 opcode = **202**：商城舞台 `CShopStageNew::OnPacket` (`sub_5D5CBA`) 里
  `v7 = opcode - 202; if (v7 == 0) → sub_5D171B(this, pkt)` 才是购买结果处理；
  op 267（有专属 case 直接 return）和 op 268（只落到 `default: goto LABEL_16` 的
  购物篮/优惠券广播）都到不了商城舞台。
  结果包体 = `E1(type) + E4(到期时间 hi) + E4(lo)`（**不含** 202，202 就是 opcode）；
  `sub_5D171B` 先用 `sub_66B4ED` 读 type 再读 8 字节到期时间（0=永久），
  然后按客户端自己选中的商品调 `sub_66A017` 把物品应用进背包；随后 `sub_5CDC64` 发
  **C→S op 294**（`E1(playerIdx)`，实测无附加负载）并回发 **C→S op 273**（action=2 的完整变更清单，
  见上一条）。两者都是纯通知，服务端不需要回包。
- **商城背包同步（C→S op 294）**：参见 `handler/shop/ShopInvenSyncReq.kt`，纯上报，读掉 playerIdx 即可。
- **背包全量同步只能走 S→C op 198（不是 320）**：客户端 `CUserInventoryMan::ReadEquippedInventory`
  (`0x67601D`) 会先**清空**两位玩家的背包/装备，再由 `ApplyEquippedInventory` (`0x6757A6`)
  逐条 `sub_65D55F` 重建 —— op 198 是背包的唯一真相源。背包物品用伪槽位 **0xFF**
  （`CUserInventory::AddItem` 0x65D55F 里 `slot == -1` 且分类非可穿戴集合 → 只 push 进背包向量 `[inv+8]`），
  令牌取 op 60 定义表里的 name。实现在 `packet/UserInventory.kt` 的 `bagInventoryEntries()`，
  `JoinSessionReq`（进频道）与 `StageInitReq`（回大厅，必跟在 198 之后补 413 保住背包等级）都会带上。
- **S→C op 320 是商城专用单件入包，不是背包列表**：`case 0x140 → sub_4FCCBF`，包体 =
  `E1(playerIdx) + E4(itemId) + E4(param) + E4(hours) + E1(a) + E1(b)`，itemId 用
  `sub_677912(dword_993D58（商城商品管理器）, ...)` 查定义 —— 在大厅/频道里查不到 → 整条静默丢弃。
  所以别拿它做背包持久化（这是「商城里买完看得到、回大厅就没了」的根因）。
- **商城连线状态上报（C→S op 316）**：`sub_5BE296` → `E1(state)`，退出商城回大厅时
  `sub_5DF695(a2==8)` 发出，纯通知无回包。见 `handler/shop/ShopConnStateReq.kt`。
  错误码走 `sub_630625` 弹窗（-32 金币不足 / -33 超上限 / -47 已拥有 / -48 背包已满 / -100 道具锁）。
