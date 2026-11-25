package bnbemu.handler

import bnbemu.codec.encodeName
import bnbemu.domain.AppearanceRules
import bnbemu.netty.PKTHandler
import bnbemu.packet.lobby.SessionList
import bnbemu.packet.lobby.SelfUserInfoEntry
import bnbemu.packet.lobby.SelfUserInfo
import bnbemu.service.PlayerService
import bnbemu.service.player

/**
 * C→S 本机玩家信息同步请求 (Opcode 310)
 *
 * 逆向确认 (CA.exe):
 * 1. 位于 TSingleton<CUserMap>::RequestUserMap (0x004E02E3, push 0x136)。
 * 2. 由 CLobbyStage 发起。空载荷包。
 *
 * 回应 op 254 是**本机玩家自身信息**（每个本机玩家一条），客户端用它画大厅左上等级面板：
 * 名牌 (+0xD4) / 段位 (+0x2C,+0x13C) / 等级 (+0x5C) / 名字 (+0x08) / 经验环 (+0x17C)。
 * 详见 `packet/lobby/SelfUserInfo.kt` 的字段表。
 */
val SelfUserInfoReq = PKTHandler { c ->
    val player = c.player ?: PlayerService.activeProfile
    c.send(
        SelfUserInfo(
            SelfUserInfoEntry(
                session = encodeName(player.charName),
                level = player.level,
                nameplate = AppearanceRules.namePlateStyle(player),
                expPercent = AppearanceRules.expPercent(player),
            )
        ),
        SessionList(),
    )
}
