package bnbemu.handler

import io.netty.util.AttributeKey
import bnbemu.netty.ClientSocket
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.netty.PassportServer
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.UserEquipEntry
import bnbemu.packet.UserInfoResult
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.server.EquipSlot

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
    val currentBomber = userSlot?.bomber?.toInt() ?: user?.bomber ?: 0
    val charItemId = when (currentBomber) {
        5 -> 5 // Boz
        4, 10, 2 -> 2082 // Marid
        else -> 1 // Dao / Bazzi
    }
    val equips = listOf(
        UserEquipEntry(itemId = charItemId, slot = EquipSlot.CHARACTER, param1 = 20, param2 = 0),
        UserEquipEntry(itemId = 10006, slot = EquipSlot.ITEM_BAG, param1 = 1, param2 = 0),
        UserEquipEntry(itemId = 2082, slot = EquipSlot.CHAR_DECO, param1 = 1, param2 = 0)
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
        UserEquipEntry(itemId = charItemId, slot = EquipSlot.CHARACTER, param1 = 20, param2 = 0),
        UserEquipEntry(itemId = 10006, slot = EquipSlot.ITEM_BAG, param1 = 1, param2 = 0),
        UserEquipEntry(itemId = 2082, slot = EquipSlot.CHAR_DECO, param1 = 1, param2 = 0)
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
 * C->S op 73: 客户端槽位角色/状态同步请求 (UserInfoReq)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x004F881E
 *   - 触发点包括:
 *     1. 商城退出: CShopStageNew::ExitShop (0x005CDAF2)
 *     2. 个人名片首次打开: CStage::OnMyPageButton (0x00501BAF)
 *     3. 切换舞台/阶段: CMainSystem::SetStage (0x0068EA26)
 *     4. 商城购买/装扮更新等 (0x005DE3B4)
 * - 包体结构: Encode1(count) + repeat(count) { Encode1(bomberId) }
 *   (日志样本: 5B 5A -> count=1, bomberId=0; 5B 5E -> count=1, bomberId=4)
 * - 关键机制与防误触发说明:
 *   客户端在此包仅上报本地各槽位选中的角色 ID (bomberId)，以同步服务端角色状态，
 *   本身并不期望服务端回显 S->C op 73 (UserInfoResult)！
 *   ⚠️ 游戏内 CMyPageDialog 唯一定义的构造与弹出点位于 0x004FBB6F (CStage::OnUserInfoResult -> 0x004FBFD4)。
 *   若服务端在此处下发 S->C op 73 UserInfoResult，客户端无论处于何种状态 (例如商城退出时)，
 *   均会无条件弹出 CMyPageDialog 对话框，导致商城退出时误打开账号资料界面！
 *   因此本 Handler 仅负责消费载荷并更新槽位角色，切勿在此下发 UserInfoResult。
 *   名片弹窗功能由 C->S op 66 (UserRestrictReq) 的 isMe==1 分支精准驱动。
 */
val UserInfoReq = PKTHandler { c ->
    val count = Decode1
    val bomberIds = (0 until count).map { Decode1 }

//    val user = c.users.firstOrNull()
//    if (user != null && bomberIds.isNotEmpty()) {
//        user.bomber = bomberIds[0]
//    }

    // 客户端上报的是各槽位已选角色 ID (bomberIds), 同步更新对应槽位角色
    val userSlot = Room.slots.find { it.user?.c == c }
    if (userSlot != null && bomberIds.isNotEmpty()) {
        userSlot.bomber = bomberIds[0].toByte()
    }
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
      - `+0x180`: Profile (个性签名, Tab 1)
      - `+0x168`: Guild (公会名, `EncodeTokenStr`)
      - `+0x164`: GuildRank (`Encode1`)
      - `+0x78`: Wins (`Encode4`)
      - `+0x7c`: Draws (`Encode4`)
      - `+0x80`: Losses (`Encode4`)（此前早期版本将 level/exp/lucci 错位写入胜负平，导致战绩显示为 5 Wins 0 Draws 1234567 Losses，现已校正）
      - `+0x8c`: curExp (`Encode4`)、`+0x90`: maxExp (`Encode4`)
      - `+0x2c`: Sweetie (伴侣名, `EncodeTokenStr`)、`+0x5c`: Favorite (最喜爱角色, `EncodeTokenStr`)
      - `+0x98`: Level (`Encode4`)
      - Lucci 金币由先行下发的 `UserDynamicInfo(0, lucci)`（op 122）写入客户端单例 `CUserDynamicInfo`。
  - 实现见 `handler/UserInfoReq.kt` + `handler/UserRestrictReq.kt` + `packet/UserInfoResult.kt`；关闭包见 `handler/WatchUserInfoReq.kt` (74) + `handler/ChangeEquipReq.kt` (273)；单元测试见 `src/test/kotlin/bnbemu/netty/MyPageTest.kt`。
  - **他人名片角色形象显示机制（S→C op 73 UserInfoResult 装备槽位列表）**：
    `COtherUserPageDialog::InitData` (`0x005E2F9B`) 调用 `0x005F488E` 设置角色装扮。客户端反序列化 `UserInfoResult` 时在 `0x0044A178` 读取 `[esi + 0xec]`（`subListCount`，装备条目数）及循环条目（`Encode4(itemId) + Encode1(slot) + Encode4(param1) + Encode4(param2)`）。若 `subListCount == 0`，客户端跳过装备查找，无法解析角色卡与外观导致他人角色形象不显示；服务端在 `UserInfoResult` 下发装备槽位（包含角色卡 `itemId=1/5/2082` 等）即可让他人形象完整渲染。
 */
