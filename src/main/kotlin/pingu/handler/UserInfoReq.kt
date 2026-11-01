package pingu.handler

import io.netty.util.AttributeKey
import pingu.netty.ClientSocket
import pingu.netty.Decode1
import pingu.netty.PKTHandler
import pingu.netty.PassportServer
import pingu.packet.UserDynamicInfo
import pingu.packet.UserInfoResult

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

    c send UserInfoResult(
        isSelf = true,
        slotIndex = slotIndex,
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
        sweetieName = ""
    )
}

/**
 * C->S op 73: 请求用户详细资料 (用于 My Page 个人中心首次打开或查看他人资料)
 *
 * 逆向确认 (CA.exe EN v18):
 * - 发包点: CA.exe 0x00501BAF (CStage::OnMyPageButton) -> 0x004F881E
 * - 包体结构: Encode1(count) + repeat(count) { Encode1(slotIndex) }
 *   (日志样本: 5B 5A -> count=1, slotIndex=0)
 * - 关键机制:
 *   客户端在 0x004F881E 发送 op 73 时会调用 0x006E4CDA 将内部标志 [0x00993E70 + 0x358] 置为 0 (锁定)。
 *   后续再次打开 My Page 时将跳过 op 73，转由 op 66 / op 301 驱动。
 */
val UserInfoReq = PKTHandler { c ->
    val count = Decode1
    val slots = (0 until count).map { Decode1 }
    val slotIndex = slots.firstOrNull() ?: 0

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
 */