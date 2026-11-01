package pingu.handler

import io.netty.util.AttributeKey
import pingu.netty.ClientSocket
import pingu.netty.Decode1
import pingu.netty.PKTHandler
import pingu.netty.PassportServer
import pingu.packet.DEFAULT_USER_EQUIPS
import pingu.packet.UserDynamicInfo
import pingu.packet.UserEquipEntry
import pingu.packet.UserInfoResult
import pingu.server.Room
import pingu.server.bomber

private val LAST_USER_INFO_SEND_TIME = AttributeKey.valueOf<Long>("LAST_USER_INFO_SEND_TIME")

/**
 * 下发用户详细资料与动态属性 (支持时间防抖 500ms)
 */
fun sendUserInfo(c: ClientSocket, slotIndex: Int = 0) {
    val now = System.currentTimeMillis()
    val last = c.ch.attr(LAST_USER_INFO_SEND_TIME).get() ?: 0L
    if (now - last < 500) {
        return
    }
    c.ch.attr(LAST_USER_INFO_SEND_TIME).set(now)

    val user = c.users.firstOrNull()
    val charName = PassportServer.lastAccount.charName.ifEmpty { user?.name ?: "ffdf" }
    val lucci = user?.lucci ?: 1234567

    // 先同步客户端 Lucci 动态属性 (CUserDynamicInfo.propertyId[0])
    c send UserDynamicInfo(0, lucci)

    // 安全约束: CMyPageDialog 仅分配 2 个槽位 (0=自己, 1=对比目标), 必须在 0..1 范围内,
    // 超过 1 会在 0x67973E ([0x009E06C0 + slotIndex * 0x438]) 发生堆越界导致 0xC0000005 崩溃闪退
    val safeSlotIndex = slotIndex.coerceIn(0, 1)

    // 根据当前槽位选中的角色动态配置角色卡与装扮
    val userSlot = Room.slots.find { it.user?.c == c }
    val currentBomber = userSlot?.bomber?.toInt() ?: 0
    val charItemId = when (currentBomber) {
        5 -> 5 // Boz
        4, 10, 2 -> 2082 // Marid
        else -> 1 // Dao / Bazzi
    }
    val equips = listOf(
        UserEquipEntry(itemId = charItemId, slot = 15, param1 = 20, param2 = 0),
        UserEquipEntry(itemId = 10006, slot = 13, param1 = 1, param2 = 0),
        UserEquipEntry(itemId = 2082, slot = 4, param1 = 1, param2 = 0)
    )

    c send UserInfoResult(
        isSelf = true,
        slotIndex = safeSlotIndex,
        characterName = charName,
        gender = 0,
        motto = user?.motto ?: "",
        guildName = "",
        guildRank = 0,
        win = user?.win ?: 0,
        draw = user?.draw ?: 0,
        lose = user?.lose ?: 0,
        curExp = user?.exp ?: 0,
        maxExp = 1000,
        level = user?.level ?: 5,
        favorite = user?.favorite ?: "Bazzi",
        sweetieName = "",
        equips = equips
    )
}

/**
 * 下发其他玩家的详细资料 (展示他人名片 COtherUserPageDialog)
 */
fun sendOtherUserInfo(c: ClientSocket, targetCharName: String) {
    // 优先从房间槽位玩家或当前服务端连接中查找
    val targetSlot = Room.slots.find { it.user?.name.equals(targetCharName, ignoreCase = true) }
    val targetUser = targetSlot?.user
        ?: c.users.find { it.name.equals(targetCharName, ignoreCase = true) }

    val bomberId = targetSlot?.bomber?.toInt() ?: 1
    val charItemId = when (bomberId) {
        5 -> 5 // Boz
        2 -> 2082 // Marid
        else -> 1 // Dao / Bazzi
    }
    val equips = listOf(
        UserEquipEntry(itemId = charItemId, slot = 15, param1 = 20, param2 = 0),
        UserEquipEntry(itemId = 10006, slot = 13, param1 = 1, param2 = 0),
        UserEquipEntry(itemId = 2082, slot = 4, param1 = 1, param2 = 0)
    )

    val win = targetUser?.win ?: 10
    val draw = targetUser?.draw ?: 0
    val lose = targetUser?.lose ?: 5
    val exp = targetUser?.exp ?: 500
    val level = targetUser?.level ?: 5
    val motto = targetUser?.motto?.ifEmpty { "PopTag Forever!" } ?: "PopTag Forever!"
    val gender = 0
    val favorite = targetUser?.favorite ?: "Bazzi"

    c send UserInfoResult(
        isSelf = false,
        slotIndex = 0,
        characterName = targetCharName,
        gender = gender,
        motto = motto,
        guildName = "",
        guildRank = 0,
        win = win,
        draw = draw,
        lose = lose,
        curExp = exp,
        maxExp = 1000,
        level = level,
        favorite = favorite,
        sweetieName = "",
        equips = equips
    )
}

/**
 * C->S op 73: 请求用户详细资料 (用于 My Page 个人中心首次打开或查看他人资料)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x00501BAF (CStage::OnMyPageButton) -> 0x004F881E
 * - 包体结构: Encode1(count) + repeat(count) { Encode1(bomberId) }
 *   (日志样本: 5B 5A -> count=1, bomberId=0; 5B 5E -> count=1, bomberId=4)
 * - 关键机制与防崩溃说明:
 *   客户端在 0x004F881E 发送 op 73 时，通过 0x006E50F0 循环读取本地各槽位选中的角色 ID (bomberId)，
 *   绝非槽位索引 slotIndex！
 *   若服务端将其误认为 slotIndex 回传给客户端，客户端 CMyPageDialog (0x005E5688 -> 0x0067973E) 寻址
 *   [0x009E06C0 + slotIndex * 0x438]（仅分配了 2 个槽位元素），当 slotIndex >= 2 时将发生堆越界访问
 *   非法虚表指针而触发 0xC0000005 崩溃闪退！
 *   展示自己名片时 slotIndex 恒为玩家在房间中的有效槽位 (通常为 0，安全范围 0..1)。
 *   客户端在 0x004F881E 发送 op 73 时会调用 0x006E4CDA 将内部标志 [0x00993E70 + 0x358] 置为 0 (锁定)。
 *   后续再次打开 My Page 时将跳过 op 73，转由 op 66 / op 301 驱动。
 */
val UserInfoReq = PKTHandler { c ->
    val count = Decode1
    val bomberIds = (0 until count).map { Decode1 }

    // 客户端上报的是各槽位已选角色 ID (bomberIds), 槽位索引恒为其在房间中的有效槽位 (通常为 0)
    val slotIndex = Room.slots.indexOfFirst { it.user?.c == c }.takeIf { it in 0..1 } ?: 0

    sendUserInfo(c, slotIndex)
}


/*
  - **打开时序与二次卡死根因**：
    1. 首次打开：客户端在 `0x00501B50` (`CStage::OnMyPageButton`) 发送 `op 301 MyPageReq`，调用 `0x006E5135` 检查内部标志位 `[0x00993E70 + 0x358]`。首次非 0，调用 `0x004F881E` 发送 `C->S op 73 UserInfoReq`，并在 `0x006E4CDA` 将 `0x358` 设为 0（锁定），最后发送 `C->S op 66 UserRestrictReq`。
    2. 关闭对话框：`CMyPageDialog` 析构发送 `op 74 WatchUserInfoReq (5A 5A -> 0, 0)` 与 `op 273 ChangeEquipReq (11 字节)`，但并不会将 `0x358` 恢复为 1。
    3. 第二次及后续打开：`0x00501B50` 检查 `0x358 == 0`，直接 `je 0x501bb4` 跳过发送 `op 73`，**仅发送 `op 301` 与 `op 66`**。
    4. 弹窗触发机制：游戏内 `CMyPageDialog` 唯一定义的构造与弹出点位于 `0x004FBB6F` (`CStage::OnUserInfoResult` -> `0x004FBFD4`)，必须收到 **`S->C op 73 UserInfoResult`** 才会弹出窗口。此前服务端仅对 `op 73` 回包且未在 `op 66` 回包，导致第二次打开时客户端因不发 `op 73` 而永久卡死等待。
    5. 服务端修复方案：在 `UserInfoReq (73)` 与 `UserRestrictReq (66)` 中统一调用 `sendUserInfo(c, slotIndex)` 下发 `op 122 UserDynamicInfo` 与 `op 73 UserInfoResult`，并加入 500ms 连接级时间防抖，既避免首次连续两包造成重复回包，又保证第二次打开由 `op 66` 顺利唤醒弹窗。
  - **字符串 Degree-3 加密与字段错位修复**：
    - 字符串 Token 编码：`CA.exe 0x0078F910` 在 degree 1..3 帧中强制调用 `0x0075DAF0` 进行 token 变换（4B LE 读 XOR 0x96CA5395 按 BE 写回；尾 2B XOR 0xA569；尾 1B XOR 0x5A）。若直接发送明文 ASCII 字符串（如 `ffdf`、`PopTag Forever!`）会被客户端解密为乱码（如 `ó5¬ð`、`Á#¥ÆÓs...`）。服务端发送字符串必须使用 `EncodeTokenStr`（基于 `encodeName`）。
    - 59 个字段与 UI 控件映射（`PUserInfoResult::Decode` `0x00449F23` 与 `CMyPageDialog::InitData` `0x005E2790`）：
      - `errCode` (E4, 0)、`isSelf` (E4, 1) + if (isSelf) `slotIndex` (E1, 0)
      - `+0x44`: Nickname (角色名, `EncodeTokenStr`)
      - `+0x74`: Gender (性别: 0=男, 1=女, `Encode1`)
      - `+0x180`: Profile (个性签名, `EncodeTokenStr`)
      - `+0x168`: Guild (公会名, `EncodeTokenStr`)
      - `+0x164`: GuildRank (`Encode1`)
      - `+0x78`: Wins (`Encode4`)
      - `+0x7c`: Draws (`Encode4`)
      - `+0x80`: Losses (`Encode4`)（此前早期版本将 level/exp/lucci 错位写入胜负平，导致战绩显示为 5 Wins 0 Draws 1234567 Losses，现已校正）
      - `+0x8c`: curExp (`Encode4`)、`+0x90`: maxExp (`Encode4`)
      - `+0x2c`: Sweetie (伴侣名, `EncodeTokenStr`)、`+0x5c`: Favorite (最喜爱角色, `EncodeTokenStr`)
      - `+0x98`: Level (`Encode4`)
      - Lucci 金币由先行下发的 `UserDynamicInfo(0, lucci)`（op 122）写入客户端单例 `CUserDynamicInfo`。
  - 实现见 `handler/UserInfoReq.kt` + `handler/UserRestrictReq.kt` + `packet/UserInfoResult.kt`；关闭包见 `handler/WatchUserInfoReq.kt` (74) + `handler/ChangeEquipReq.kt` (273)；单元测试见 `src/test/kotlin/pingu/netty/MyPageTest.kt`。
  - **他人名片角色形象显示机制（S→C op 73 UserInfoResult 装备槽位列表）**：
    `COtherUserPageDialog::InitData` (`0x005E2F9B`) 调用 `0x005F488E` 设置角色装扮。客户端反序列化 `UserInfoResult` 时在 `0x0044A178` 读取 `[esi + 0xec]`（`subListCount`，装备条目数）及循环条目（`Encode4(itemId) + Encode1(slot) + Encode4(param1) + Encode4(param2)`）。若 `subListCount == 0`，客户端跳过装备查找，无法解析角色卡与外观导致他人角色形象不显示；服务端在 `UserInfoResult` 下发装备槽位（包含角色卡 `itemId=1/5/2082` 等）即可让他人形象完整渲染。
 */