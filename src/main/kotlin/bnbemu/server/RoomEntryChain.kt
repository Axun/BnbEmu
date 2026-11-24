package bnbemu.server

import bnbemu.codec.encodeName
import bnbemu.domain.MAX_SESSION_SLOTS
import bnbemu.netty.ClientSocket
import bnbemu.packet.lobby.EnterSessionResult
import bnbemu.packet.room.NpcAvailability
import bnbemu.packet.room.PracticeSetPlayer
import bnbemu.packet.room.SetPlayer
import bnbemu.packet.room.PracticeSetSlotAI
import bnbemu.packet.room.PracticeSlotReady
import bnbemu.packet.lobby.RoomEntry
import bnbemu.packet.lobby.RoomList
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.room.SetSlotColor
import bnbemu.packet.room.SetSlotFanfare
import bnbemu.packet.UserSimpleInfoResult
import bnbemu.packet.WelcomeNotice
import bnbemu.packet.room.RoomName
import bnbemu.packet.room.SelectMap
import bnbemu.service.PlayerService
import bnbemu.service.player

/**
 * 整条「进房间」S→C 推送链（练习房基准）。
 *
 * 原来内联在 `handler/CreateSessionReq.kt`（C→S op 75 的回应），
 * 抽出来给「对局结束回房间」复用：`Room.backToRoom` 发完 S→C op 155 之后，
 * 客户端虽然已经切回 stage 12，但房间对象/会话是空的 —— 不补这条链，房主位、座位、
 * 房间名、选图都会是降级状态。
 *
 * 线序与 tst.pcap stream4 一致：
 *   [87 RoomList, 83 EnterSessionResult, 4(13B), 4(65B), 113, 100(78B), 108,
 *    106×8, 每槽(114, 104, 105) + 下一槽的 103, WelcomeNotice]
 */
fun sendRoomEntryChain(c: ClientSocket, room: Room, init: Boolean = true) {
    val player = c.player ?: PlayerService.activeProfile
    val closedSlots = room.slots.count { (it.state.toInt() and FLAG_CLOSED) != 0 }
    val humanCount = room.slots.count { it.isHuman }.coerceAtLeast(1)
    val roomEntry = RoomEntry(
        roomId = room.sessionId,
        handle = player.userId,
        name = room.name,
        hasPassword = room.password.isNotEmpty(),
        curPlusClosed = humanCount + closedSlots,
        closedSlots = closedSlots,
        maxPlusClosed = MAX_SESSION_SLOTS,
        sessionType = room.sessionType,
        mapId = room.mapId,
    )
    c.send(
        RoomList(listOf(roomEntry)),
        // 进房结果 (op 83): 房型必须原样回显, 客户端 0x0068626F 存进 CFxContext[0x570]。
        EnterSessionResult(sessionId = room.sessionId, sessionType = room.sessionType),
        UserSimpleInfoResult(), SessionInfoResult(userId = player.userId, session = encodeName(player.charName)),
        // op 113 房名同步: 客户端把字符串直接写进会话对象 +0x1C4 并刷新房间标题。
        // 必须发 room.name 而不是空串 —— 空串会把 op 87/op 83 刚填好的房名清掉（变空白）。
        RoomName(room.name),
        SetPlayer(slotId = 0, slot = room.slots[0], player = c.player),
    )
    if (init) {
        c.send(SelectMap(result = 0, themeId = room.themeId, mapId = room.mapId))
    }

    // 106×8 —— 按服务端权威的 room.slots[].state 回灌「封闭 / 开放」位。
    //
    // 客户端 CSlotStage::OnPacket 的 op106 处理器 (0x004EFCBE) 实际做的事:
    //   v5 = Decode1((slot<<4)|state)
    //   sub_687914(ctx, slot, &meta)            读该槽 3B meta
    //   LOBYTE(meta) = (v5 ^ meta) & 1 ^ meta  只把 meta[0] 的 bit0 设成 state&1
    //   sub_687169(ctx, slot, meta)            写回, 随后 sub_4EF87F 逐槽按 bit0 选
    //                                         "PRE_Bt_Slot-Open / -Close" 按钮贴图
    // 即 state&1 == 1 ⇔ FLAG_CLOSED (封闭)。旧实现 8 个槽一律发 state=2，等于每次
    // 重放进房链都把玩家踢掉的槽位重新开放 —— 这就是「踢掉几个电脑、打一局回来
    // 槽位不再封闭」的根因。链里后续的 104/105/114 分别只改 meta[2]/meta[1]/UI 子对象
    // (0x4EFB48 / 0x4EFBF6 / 0x4EFD42 均不碰 bit0)，所以开头设一次就能保住。
    repeat(8) { slot ->
        val closed = (room.slots[slot].state.toInt() and FLAG_CLOSED) != 0
        c.send(PracticeSlotReady(slot, state = if (closed) 1 else 2))
    }

    if (room.isAIBattle) {
        val refSlot = room.chiefSlotIndex()
        repeat(8) { slot ->
            val s = room.slots[slot]
            val slotBomber = s.bomber.toInt()
            c.send(
                SetSlotFanfare(slot, bomber = slotBomber),
                SetSlotColor(slot),
                PracticeSetPlayer(slot, bomber = slotBomber),
            )
            // 激战模式只有 4..7 号槽属于 NPC 队伍; refSlot 指向房主真人槽 (0),
            // 避免 1..3 真人空槽误挂载机器人。
            if (slot in 4..7) {
                c.send(
                    PracticeSetSlotAI(
                        slot = slot,
                        isAI = s.isAI && !s.isHuman,
                        refSlot = refSlot,
                        aiLevel = s.aILevel,
                    )
                )
            }
        }
        c.send(NpcAvailability())
    } else {
        // 每槽先发 [114, 104, 105]，随后补「下一槽」的 AI 槽属性包 103 (槽号 = 当前组 + 1)。
        // 只有槽 0 是真人，槽 1..7 全是 AI，op 103 从不用于真人槽。
        repeat(8) { slot ->
            c.send(
                SetSlotFanfare(slot),
                SetSlotColor(slot),
                PracticeSetPlayer(slot, bomber = room.slots[slot].bomber.toInt()),
            )
            // op 103 第 3 字段 refSlot = 槽位引用 (抓包等于槽号, 客户端要求 ≤7)，
            // 第 4 字段 aiLevel = 难度档 0..4 (抓包初始 = 1)。
            //
            // ⚠️ 这条链同时被 Room.backToRoom / ShopToRoomReq 复用：
            //    旧实现把 aiLevel 写死 1、isAI 写死 true，于是每次回房都把客户端全局槽位
            //    结构 +8 (0x685E9B 写入 / 0x686A4C 读回，也是 +/- 按钮的起算值) 冲成默认档，
            //    表现为「对局结束后设置的 AI 等级回到 1」，并且会把玩家踢掉的 AI 加回来。
            //    现在改为读 room.slots 的服务端权威状态回灌；客户端不保存房间设置，
            //    重放进房链时服务端必须把 isAI / aILevel 一并带回。
            if (slot < 7) {
                val idx = slot + 1
                val s = room.slots[idx]
                c.send(
                    PracticeSetSlotAI(
                        slot = idx,
                        isAI = s.isAI && !s.isHuman,
                        refSlot = idx,
                        aiLevel = s.aILevel,
                    )
                )
            }
        }
    }
    c.send(WelcomeNotice())
}
