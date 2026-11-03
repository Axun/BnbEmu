package bnbemu.packet.room

import bnbemu.netty.PKT

// ============================================================================
// S→C op 172 MapEventSync —— 地图事件(波)时间轴同步
// ============================================================================
// 逆向结论 (CA.exe):
//   分发: 局内分发器 0x0055A76D `sub eax,0xAC; cmp eax,0x18; jmp [eax*4+0x55AA64]`
//         表项 0 → 0x0055A7B0 → call 0x00559FA1 (处理器)
//   处理: 0x00559FA1 按下列顺序读字段, 两个会话字段校验不过则**整包静默丢弃**,
//         通过后把 (eventId, timeMs, 三组条目数组) 交给 CGameContext::OnEvent 0x00533DBE。
//   分发: 0x00533DBE 遍历 CGameContext+0x3D558 的事件链表, 用 0x0047201F 取事件的
//         `[event+0x104]` 事件 id 与包内 eventId 比对, 命中后调用 0x0047299C 激活事件。
//   激活: 0x0047299C 把 "Wave/" + 事件波文件名交给 0x767920 播放; 飞机事件的默认波文件是
//         "Game/AirPlane1" (0x0089377C), 对应 .?AVCAirPlaneEvent@@ / .?AVCMapActionAirplane@@。
//
// body = E2(sessionA) E2(sessionB) E1(eventId) E4(timeMs) E1(count)
//        + count × [E1 a + E2 b + E1 c + E1 d]
//   - sessionA: 必须等于 [session->0x30CC]->+0x3E080 (getter 0x00532BC3); 抓包恒 176
//   - sessionB: 必须等于 0x00687C57 的返回值 (经 0x4DC798 读取会话+0x1F4); 抓包恒 29
//   - eventId : 事件 id; 抓包恒 1
//   - timeMs  : 事件时钟 = 对局已进行的毫秒数 (13 样本 401/801/1201/1601/2002/.../5203)
//   - count   : 条目数, 处理器用 0x0055EAAD 校验不超过地图容量; 抓包恒 1
//   - 条目    : 抓包 13 样本恒为 (0, 0, 0, 秒计数), 末字节每秒 +1
//
// ⚠️ 命名订正: 旧名 ItemThrow / "AI 道具抛掷" 是错的。客户端 AI 完全在本地运行
//    (CAIPlayer / CAIGoal* 系列, 如 .?AVCAIGoalBomb@@), 与本包无关; 对局推进也不需要
//    服务端周期广播本包。真实服务器用它驱动地图事件(飞机波)的时间轴。
// ⚠️ EN 真实 opcode = 172 (tst.pcap stream4 sq=343~400 逐包 CRC8 验证), 不是局内 +200 偏移的 374。
fun MapEventSync(
    sessionA: Int,
    sessionB: Int,
    eventId: Int,
    timeMs: Int,
    entries: List<MapEventEntry> = emptyList()
) = PKT {
    Encode2(sessionA)
    Encode2(sessionB)
    Encode1(eventId)
    Encode4(timeMs)
    Encode1(entries.size)

    entries.forEach { entry ->
        Encode1(entry.a)
        Encode2(entry.b)
        Encode1(entry.c)
        Encode1(entry.d)
    }
}

// 条目字段语义尚未钉死: 抓包 13 样本恒为 (a=0, b=0, c=0, d=秒计数)。
class MapEventEntry(
    val a: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val d: Int = 0
)
