- **装备槽位编码与「回大厅装备被自动脱掉」的坑（2026-11-08）**：
    - op 273 的 `E1(equipSlot)` 是**复合槽位 = 24 × 装备大类 + 子槽位**
      （`sub_65C81A` 557216 写入槽项 +116，即物品 +84；`sub_659004` 553095 原样发回。
      实证：蓝妹妹 2082 上报 `0x94 = 148 = 6×24+4`）。大类由物品 category 经 `sub_67884E` 推导：
      cat 1/3→8、2/9/20/33/36/41→7、4/5/6/**10(背景/旗帜/边框)**/18/28/29→9、12/14→10、16→6、19→5、42→4。
      ⇒ **装扮背景（cat 10）槽位是 216~239，只有 0xFF 才是「背包伪槽位」**，不得再设 200 之类的上限。
    - op 198 / op 73 的 `E1(slot)` 只放**子槽位**（客户端 `sub_65D55F` 用 category 自行还原大类，
      再 `slot % 24`），所以抓包里 2082 是 slot=4、角色卡 15、道具栏 13；回放时要 `% 24`。
    - op 198 是客户端**唯一全量真相**（`sub_67601D` 先清空两位玩家的背包+装备，`sub_6757A6` 逐条
      `sub_65D55F` 重建，末尾 `sub_659334` 按 13×24 重铺纸娃娃）：**每次回大厅重走进频道握手
      （op 81）都会重发 198**，清单里没有的装备一律被清掉。所以 273 记录下来的装备必须并入
      `inventoryEntries()`（`packet/UserInventory.kt`）下发，否则表现为「商城设了背景，回大厅自动取消装备」。
    - 实现：`packet/UserInventory.kt` 的 `equipGroupOfCategory` / `equipSlotKey` / `equippedInventoryEntries` /
      `inventoryEntries`；回归测试 `src/test/kotlin/bnbemu/netty/BackgroundEquipTest.kt`。
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
- **角色卡**：
    - 角色卡按角色分 id：**item 1 = Dao、item 5 = Boz**（实证：`cap/chan_s2c_op198_137B_0.bin`
      `cap/practice_s2c_op198_137B_0.bin`），条目格式固定 `slot=15 param=20 status=192 duration=240000`。
      改角色卡一律走 `packet/UserInventory.kt` 的 `bomberCharCard(bomber)`（op 198 与 op 73 共用一份映射）。
    - op 60 ItemDef不按当前角色生成也不会把角色换掉，对角色装扮有没有影响还待测试
