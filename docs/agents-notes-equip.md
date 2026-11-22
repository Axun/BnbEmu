- **装备槽位编码与「回大厅装备被自动脱掉」的坑**：
    - op 273 的 `E1(equipSlot)` 是**复合槽位 = 24 × 装备大类 + 子槽位**
      （`sub_65C81A` 557216 写入槽项 +116，即物品 +84；`sub_659004` 553095 原样发回。
      实证：蓝妹妹 2082 上报 `0x94 = 148 = 6×24+4`）。大类由物品 category 经 `sub_67884E` 推导：
      cat 1/3→8、2/9/20/33/36/41→7、4/5/6/**10(背景/旗帜/边框)**/18/28/29→9、12/14→10、16→6、19→5、42→4。
      ⇒ **装扮背景（cat 10）槽位是 216~239，只有 0xFF 才是「背包伪槽位」**，不得再设 200 之类的上限。
    - op 198 / op 73 的 `E1(slot)` 只放**子槽位**（客户端 `sub_65D55F` 用 category 自行还原大类，
      再 `slot % 24`），所以抓包里 2082 是 slot=4；回放时要 `% 24`（抓包里的 slot=15 角色卡与 slot=13 道具栏见下方结论）。
    - op 198 是客户端**唯一全量真相**（`sub_67601D` 先清空两位玩家的背包+装备，`sub_6757A6` 逐条
      `sub_65D55F` 重建，末尾 `sub_659334` 按 13×24 重铺纸娃娃）：**每次回大厅重走进频道握手
      （op 81）都会重发 198**，清单里没有的装备一律被清掉。所以 273 记录下来的装备必须并入
      `inventoryEntries()`（`packet/UserInventory.kt`）下发，否则表现为「商城设了背景，回大厅自动取消装备」。
    - ⚠️ **真实客户端 CA.exe 卸下装备的发包协议**：
      `CA.exe` 唯一序列化 op 273 的函数 `sub_659004` (0x00659004) 在遍历装备槽时：
      `0x659043: test eax, eax; je 0x65908a`，即 **itemId=0 的槽位直接跳过**，客户端**绝不会**发 `(slot, 0)`！
      卸下的物品经 `sub_65CF4E` 移入背包向量（`slot=0xFF`），在第二段遍历中上报为 `(slot=0xFF, itemId)`。
      若服务端只当成背包新增物品而未从 `user.equips` 中移除，会导致装备永远脱不下来。
      修复：收到 `slot=0xFF` 时检测若在 `user.equips` 则执行卸下并回包。
    - ⚠️ **房间内脱掉装备/换装后外观不刷新的根因**：
      客户端在房间阶段关闭背包 (`CInventoryWnd`) 仅发 op 273，本地槽位 UI (`CSlotView`) 不会自动重绘，
      必须由服务端广播 **S→C op 100 `SetPlayer`**，客户端收到后由 `sub_4F7022` -> `sub_4F6E89`
      按三段清单清空并重绘槽位外观（清单无背景即彻底擦除）。
    - 实现：`packet/UserInventory.kt` 的 `equipGroupOfCategory` / `equipSlotKey` / `equippedInventoryEntries` /
      `inventoryEntries`；`handler/ChangeEquipReq.kt`；回归测试 `src/test/kotlin/bnbemu/netty/BackgroundEquipTest.kt`。
- **房间内装扮显示（S→C op 100 / CSlotAccessoryPacket）**：
    - `SetPlayer` 尾部的 `CSlotAccessoryPacket` 不是固定 11B 全零块，而是**玩家装备快照**：
      `E2(保留) + E1(count1) + count1×[E1(idx)+E2(value)] + E1(count2) + count2×[E4×3] +
      E1(f29=255) + E1(f30=0) + E4(f32=0) + E1(count3) + count3×[E4×3]`
      （解码 `sub_44A856` / 0x0044A856）。旧实现把 count2 直接写在 count1 之后，只有三段全空时才与抓包逐字节相同，
      一旦带装扮就整体错位。
    - ⚠️ 客户端收到 op 100 会**先清空**该槽位装扮再仅凭这三段重建：`sub_4F6E89`(0x004F6E89)
      清 9 个装扮槽 + 9 个特效槽后按 count1/count2 回填，`sub_4F6B51`(0x004F6B51) 经
      `sub_679271` 清 12 个纸娃娃部位后按 count3 回填 ⇒ **三段全 0 = 命令客户端把背景全部脱掉**，
      这是 op 273 已记下 `slot=0xDC(220) item=710418` 却看不到背景的根因。
    - 三段清单按 op 60 ItemDef 的 category 分流（客户端同款分支见 0x004F1780 附近 / 240389~240422，
      本地构造见 `sub_6A7396` 0x006A7396 与 `sub_6A72C2` 0x006A72C2；物品 +48/+60/+72
      = skinId/targetCharId/param，见 `sub_65A034` 0x0065A034）：
      · count1 ← **category 10**（背景/旗帜/边框）：`(idx=targetCharId, value=skinId)`，idx ≤ 8。
        710418 "Slot background 418" = targetCharId 1 / skinId 418 ⇒ 条目 `(1, 418)`；
        复合槽位由 `sub_6580F2` 算成 `24×9 + (1+3) = 220`，与 op 273 实测一致。
      · count2 ← **category 12**（角色特效）：`(skinId, targetCharId, param)`，
        客户端校验 `(skinId+2)<=9 / targetCharId<=8 / param>=0`。
      · count3 ← **category 19**（纸娃娃）：同三元组，`CGOAvatar::sub_458CD3` 要求 `param>0`，
        targetCharId 为 0..11 部位索引（`sub_65816C`）。
    - 实现：`packet/room/SetPlayer.kt` 的 `slotAccessoriesOf(equips)`（按复合槽位排序，从
      `user.equips` 查 ItemDef 生成三段清单），`SetPlayer(..., equips = user?.equips.orEmpty())`；
      无装备时输出仍与 `cap/practice_s2c_op100_*.bin` 逐字节一致。回归 `SetPlayerTest.kt` 11 项。
- **角色卡与基础角色机制**：
    - **`cat=15` 基础角色卡（`itemId=1 Dao`、`5 Boz` 等，`slot=15`）在 EN v18 客户端完全无效**：
      `sub_67884E(15)` 返回 `0`（不可穿戴大类），在 `op 198` (`sub_65D55F` → `sub_65C81A`) 与 `op 274` (`sub_4FCCBF`) 入口首行即 `if (v41 == 0) return 0` 直接丢弃；既不进入背包向量（`CInventoryWndNew` 分页 `sub_658C8F` 仅显示大类 `{5,8,9,10}`，全 exe 仅大喇叭框 `CSpeakerHotKeyDlg`/`CSpeakerMultiLineDlg` via `sub_67777B(..., 15, ...)` 显式查询 `cat=15`），也不进入装备槽。`itemId=10006 (cat=13 Item/ItemBag)` 同理（`sub_67884E(13)==0`，背包容量改由 `op 20` `bagLevel` 与 `op 413` 控制）。
    - **装扮 / 纸娃娃 / 商城预览 / 他人名片均不读取 `cat=15`**：
      - 自身纸娃娃/装扮：`CAvatarContextMyInfo::sub_67973E` 与 MyPage (`sub_5DEE4E`)、商城预览 (`sub_5CE575`) 读取 **`op 20` `bomber`** 与 **`equip[4/5/6/9/10]`**（装饰大类由 `sub_676EB7` 限定 `{5,6,10,12,14,18,19,20,28,29}`）；`sub_65631F` 仅处理 `cat=1`。
      - 房间/对局他人形象：走 `op 100 SetPlayer` 元数据 `bomber` + 三段装扮清单 (`cat 10/12/19`) + `f32` (`cat 14`)，与 `op 198` 无关。
      - 名片 `op 73 UserInfoResult`：`equips` 列表在 `sub_449F23` 存入 `a2[59]/a2[60]`，全 exe 仅 `CSeasonLadderWnd::sub_5F488E` 读取该列表显示赛季天梯奖杯；`COtherUserPageDialog::InitData` (`sub_5E2F9B`) 走 `sub_5DE6E0` 渲染角色与装扮。
    - **真正控制角色与进阶解锁的通道**：
      1. 当前角色：`S→C op 20 UserDynamicInfo` 第 12 栏（`CUDI+3s+111`）、`op 105`、`op 100`；
      2. 表情/动作模型表：`op 60 ItemDef` 重建 `cat=3` 条目为当前角色 `name` / `skinId`（不需伪造 `2..7` 角色卡）；
      3. 进阶角色卡（如蓝妹妹 `2082`）：走 **`cat=16`**（`sub_67884E(16)==6`）存入 `equip[6][targetCharId]`（复合槽 `148 = 6×24+4`），由 `sub_671C3A` → `sub_65975C` 读取。
    - 抓包里 `op 198` 的 `itemId=1 (slot=15)` 与 `op 200` slot 7 的 `(1, "Dao")`（客户端 `sub_672746` 查背包找不到还会回 `C→S 274` 清零）均为服务端历史遗留冗余数据；模拟器不再将基础角色卡或 `10006` 写入 `PlayerProfile.inventory` / `op 198` / `op 200` / `op 274` / `op 73`。
