package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler

// C→S (EN op 121 / 0x79) —— 局内「发射飞镖 (CGODart / Item/ThrownDart)」上报请求。
//
// 逆向结论 (CA.exe, Locale=EN / v18):
//   * 全 .text 唯一发包点 0x00539CC2 (sub_539CC2): CSendPacket_BeginOp(121) 之后依次写
//     E1(常量 0) + E2 + E2 + E1 + E2 = 8B, 数据取自 CGameMsgCache 的
//     [cache+92] + 24*idx 待确认队列 (sub_5395D4 填充, sub_53961A 管 200ms 冷却,
//     OnTick 0x0053A700 flush 完即清 active 并记 timeGetTime —— 本包无需服务端回应)。
//   * 唯一入队点 0x00465EC8 (发射飞镖): 本地创建 CGODart (sub_44CBC9, 贴图 "Item/ThrownDart")
//     并沿朝向逐格 40px trace, 再 sub_5395D4(cache, idx, 40*grid, 40*(grid+1), dir, i) 入队。
//   * 载荷 8B = E1(reserved) E2(startPixelX) E2(startPixelY) E1(direction) E2(rangePx)
//     - reserved     : 客户端写死 0 (0x00539CC2 第一栏传常量 0), 暂作保留/子类型栏
//     - startPixelX  : 40 * 角色所在格 (0x00462AA6 = 角色像素坐标 / 40)
//     - startPixelY  : 40 * (角色所在格 + 1), +1 格是飞镖精灵的渲染偏移
//                      (sub_44CBC9 再把 y 减 20px 做居中)
//     - direction    : bomber 朝向 (0x00465EC8 虚表 +180), 配合 dword_9886E4/9886E8
//                      方向增量表逐格 trace 命中目标 / 撞墙
//     - rangePx      : 射程像素 (for i = 40; ; i += 40), /40 即飞镖飞过的格数
//   * ⚠️ 本包**不含水泡/命中目标坐标, 也不含发射者槽位** (槽位只作队列下标, 不上线)。
//     命中对象由同一次发射的 C→S op 108 上报: E2(objId) E1(gridX) E1(gridY) E2(delayMs),
//     只有 trace 命中对象 (sub_534520 + 对象 +8/+192 非零) 时才发; 撞墙/越界则只有本包。
//   * ⚠️ EN 局内 S→C 分发器 0x0055A4D8 没有 121 分支 (落 LABEL_108 静默丢弃), 不要回 op 121。
//   * 当前实现只解析 + 调试输出 (空消费): 不回包、不广播、不改 Room 状态。
val ThrowDartReq = PKTHandler { c ->
    if (readableBytes() < 8) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val reserved = Decode1
    val startPixelX = Decode2
    val startPixelY = Decode2
    val direction = Decode1
    val rangePx = Decode2

    // 40px = 1 格; Y 起点带 +1 格渲染偏移, 反推回角色所在格
    val startGridX = startPixelX / 40
    val startGridY = startPixelY / 40 - 1
    val rangeGrids = rangePx / 40

    if (debugMode) {
        println(
            "[op121] dart: reserved=$reserved start=($startPixelX,$startPixelY)px " +
                "~grid($startGridX,$startGridY) dir=$direction range=${rangePx}px/${rangeGrids}grid"
        )
    }

    // 空消费: 不回包、不广播、不改 Room 状态。
    // 防御式消费: 未来客户端追加字段时不影响下一包解析。
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
