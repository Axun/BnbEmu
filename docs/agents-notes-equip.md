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
- **角色卡**：
    - 角色卡按角色分 id：**item 1 = Dao、item 5 = Boz**（实证：`cap/chan_s2c_op198_137B_0.bin`
      `cap/practice_s2c_op198_137B_0.bin`），条目格式固定 `slot=15 param=20 status=192 duration=240000`。
      改角色卡一律走 `packet/UserInventory.kt` 的 `bomberCharCard(bomber)`（op 198 与 op 73 共用一份映射）。
    - op 60 ItemDef不按当前角色生成也不会把角色换掉，对角色装扮有没有影响还待测试
