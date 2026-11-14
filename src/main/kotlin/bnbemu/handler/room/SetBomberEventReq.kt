package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SetBomberEvent
import bnbemu.packet.room.bombFlagLaunched
import bnbemu.server.Room

// 局内角色/AI 放置水泡请求 (C→S op 106)
// 逆向定位: CA.exe 0x006375A1 / 0x00534CCA -> CBombBag::0x0044C056 -> CGameMsgCache::0x005391AB -> 0x00539A4B (push 0x6a)
// 触发机制: 角色 (玩家 Slot 0 或 AI Slot 1~7) 放置水泡时，先在本地分配待确认水泡并生成全局唯一 bombToken (slotId + 8*k)，随后发出本包。
// 载荷结构:
// 定长部分 (11B) = E2(sessionId) E1(slotId) E4(bombToken)
//                   E1(originGridX) E1(originGridY) E1(reservedField) E1(bombFlags)
//   · sessionId     : sub_532BC3(gameSession) 场次 id, op 139 开头要校验, 必须原样回显
//   · bombToken     : 客户端待确认水泡令牌 (0x0044BFE0: slotId + 8*k)
//   · originGridX/Y : 发射者(角色)所在格, 坦克炮泡这里是炮口而非落点
//   · reservedField : 客户端 v6+36 保留栏 (sub_539A4B 的 E1(*(v6+36))), 接收侧读出即丢弃
//   · bombFlags     : 低 4 位 = 威力; 0x80 / 0x40 / 0x20 是 sub_539A4B 按队列状态置位的特性位
// 变长部分 (bombFlags & 0x20, 即发射型/坦克水泡, +5B → 16B):
//   E1(landingGridX) E1(landingGridY) E1(launchDirection) E2(flightRange)
//   · 前 3 个 E1 就是客户端 sub_54A3EA → sub_534C3D → sub_4598AB 写入水泡 +156/+168/+304 的
//     格子落点与方向 (sub_459A0B 按 40px/格换算像素)。S→C op 139 必须把这 3B 原样补回,
//     否则客户端会把 bombId 的字节当成落点, 水泡被画到屏幕外。
//   · flightRange 只上报、客户端在 op 139 不读, 无需回显。
//
// 服务端处理:
//   1) 用落点格 (无扩展时退回发射格) 调用 Room.addBomb 注册水泡 2.5s 倒计时;
//   2) 回显 S→C op 139 (SetBomberEvent)，原样带回 bombToken / bombFlags / 落点扩展及服务端分配的 bombId，
//      客户端在 0x0054A3EA -> 0x0044BD9B 匹配 bombToken 后将 bombId 写入水泡对象 (+0x90) 并扣减待确认计数。
//   3) ⚠️ 发射型水泡需要延时补一包 S→C op 138 SetBombState(bombId, state=1)「落地激活」:
//      · 普通泡由 sub_45AB56 写入 +192=1 (已激活)、+292=0 (非飞行)，2.5s 后单发 state=0 就能引爆;
//      · 发射泡被 op139 的 sub_534C3D -> sub_4598AB 改成 +192=0 (未激活)、+292=1 (飞行中)，
//        且只 sub_5606C6 清旧格、不 sub_560693 占新格。sub_565E9F 引爆要求水泡已激活。
//      · 客户端本地撞到图钉 (obstacleType=4) 时有补偿 `if (+192 == 0) sub_45976E(this,-1)` 再引爆
//        (CA.exe.c:79375) —— 这就是「只有射到钉子才会炸」的原因; 服务端 state=0 路径 (sub_534B18)
//        没有这段补偿, 直接调 sub_565E9F 对未激活泡无效, 水泡永远不炸。
//      · sub_534B18 的 state=1 分支正好调 sub_45976E(v7,-1) 设 +192=1、清 +292、sub_560693 占格,
//        且不覆盖 bombId (a2 == -1 时不写 +0x90), 所以后续 state=0 仍可按同一 id 找到这颗泡。
//      · 顺序上 op139 必须先到 (创建/匹配水泡), state=1 后到。但不能紧跟着发 —— 会截断发射动画,
//        所以由 Room.scheduleLaunchedBombActivation 延时 launchedBombActivationDelayMs (默认 500ms) 下发;
//        **不改 expireAt**, 2.5s 引信仍从收到 op106 起算。到点若水泡已被提前引爆 (飞镖/钉子
//        onObjectHit 或 onTick 到期移除) 则 Room.activateLaunchedBomb 直接跳过, 不给死泡发激活包。
// server = CGameSession::OnSetBomberEvent
val SetBomberEventReq = PKTHandler { c ->
    if (readableBytes() < 11) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val sessionId = Decode2
    val slotId = Decode1
    val bombToken = Decode4
    val originGridX = Decode1
    val originGridY = Decode1
    val reservedField = Decode1
    val bombFlags = Decode1

    val isLaunchedBomb = (bombFlags and bombFlagLaunched) != 0
    // 残包防御: 标志声明了扩展但字节不足时退回发射点, 不能越界读导致 exceptionCaught 断线。
    // 注意回显包仍按 isLaunchedBomb 补 3B (由 bombFlags 决定), 保证客户端读序始终对齐。
    val hasLandingData = isLaunchedBomb && readableBytes() >= 5
    val landingGridX = if (hasLandingData) Decode1 else originGridX
    val landingGridY = if (hasLandingData) Decode1 else originGridY
    val launchDirection = if (hasLandingData) Decode1 else 0
    val flightRange = if (hasLandingData) Decode2 else 0

    if (debugMode && isLaunchedBomb) {
        println(
            "[op106] launched bomb: slot=$slotId origin=($originGridX,$originGridY) " +
                "landing=($landingGridX,$landingGridY) dir=$launchDirection range=$flightRange " +
                "token=$bombToken flags=0x${bombFlags.toString(16)}"
        )
    }

    // 防御式消费: 客户端未来追加字段时不影响下一包解析。
    if (readableBytes() > 0) skipBytes(readableBytes())

    if (slotId in 0..7) {
        Room.slots[slotId].gridX = originGridX
        Room.slots[slotId].gridY = originGridY
    }

    val bombId = Room.addBomb(
        slotId = slotId,
        gridX = landingGridX,
        gridY = landingGridY,
        isSpecial = isLaunchedBomb,
        bombId = bombToken,
        broadcastInitialState = false
    )

    val pkt = SetBomberEvent(
        slotId = slotId,
        originGridX = originGridX,
        originGridY = originGridY,
        bombToken = bombToken,
        bombId = bombId,
        sessionId = sessionId,
        reservedField = reservedField,
        bombFlags = bombFlags,
        landingGridX = landingGridX,
        landingGridY = landingGridY,
        launchDirection = launchDirection
    )

    Room.BC(pkt)

    if (isLaunchedBomb) {
        // 等发射动画播完再落地激活; 引信不延长, 到点若泡已被提前引爆则跳过。
        Room.scheduleLaunchedBombActivation(bombId)
    }
}
