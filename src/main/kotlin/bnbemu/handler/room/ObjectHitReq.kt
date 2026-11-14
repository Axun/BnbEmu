package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C→S (EN op 108 / 0x6C) —— 局内「对象命中 / 延时事件」上报请求。
//
// 逆向结论 (CA.exe, Locale=EN / v18, 2026-11-14 定案):
//   * EN 客户端全部 5 个 C→S 108 发包点共用同一 6B 结构 (CSendPacket_CtorWithOp(v, 108)):
//       0x00465EC8 飞镖命中: delay = 1200*射程px/1000 - 2*ping, 命中格 = 射线 trace 到的那一格
//       0x0046D546 C2S_Op108_And_Op154_Action1: delay 恒 0, 同函数还发 op154 action=1
//       0x0046DE5B 通用上报: 参数 (对象, delayMs), 坐标取对象 +156/+168
//       0x0054CABA 碰撞/推挤路径: delay=300
//       0x0046DFAD 起的水泡命中路径: delay=0, 发完随即 sub_565E9F 本地引爆炸弹
//     字段宽度用 op126 DestroyBoxReq (3×Packet_Write_v68_Alias + 1×Packet_Write_v72 = 5B)
//     交叉验证: v68_Alias = E1(1B), v72 = E2(2B)。
//   * 载荷 6B = E2(targetObjectId) E1(gridX) E1(gridY) E2(delayMs)
//     - targetObjectId = sub_44B67A(obj+144), 客户端对象的混淆/校验 ID。
//       ⚠️ 不是服务端的 bombToken / Room.bombs[].id, 不能直接拿它查水泡。
//     - gridX/gridY = obj+156 / obj+168 的格子坐标, 飞镖路径即命中格 (水泡) 所在格;
//       字段序与 op106/op107 的 (gridX 行 0..12, gridY 列 0..14) 一致
//       (sub_534520(map, gridX, gridY) 与方向增量表 dword_9886E4/9886E8 交叉验证)。
//     - delayMs = 事件延时; 飞镖路径可按 1200px/s 反推射程 (射程px ≈ delayMs*1000/1200)。
//   * 实测样本 (2026-11-14 15:50:20) A7 75 5C 5F A5 09
//     → objId=540, grid=(6,5), delay=96ms (≈80px = 2 格射程, ping≈0)。
//   * ⚠️ 2026-11-14 二次逆向修正: 飞镖命中**不会**在客户端本地引爆, 必须等服务端下令。
//     - 0x00465EC8 命中分支只有 CSendPacket_CtorWithOp(v13, 108) + CSendPacket_Send,
//       全函数没有任何 sub_565E9F (引爆) 调用; CGODart 只是本地飞行动画 + 射线命中判定,
//       命中结果通过本包上报, delayMs 正是飞镖飞行时间 (1200px/s, 减 2×ping)。
//     - 上一版误引的 sub_565E9F(0x0046DFAD) 属于水泡自身帧更新
//       (sub_5B67BA vtable 调用 → sub_46DFAD, 撞障碍/滑行到位时本地引爆),
//       与飞镖是两条链, 不能泛化。
//     - 服务端下令引爆的入口是 S→C op138 SetBombState(bombId, state=0):
//       0x005481BD → 0x00534B18 → 0x00534338 按 bombId 找水泡 → 0x00565E9F 引爆。
//     ⇒ 真正闭环 (已实现): 按 gridX/gridY 在 Room.bombs 定位水泡, 延时 delayMs 后广播
//       SetBombState(bomb.id, 0); objId 是客户端混淆 ID, 不能当 bombId 用。
//   * ⚠️ 真端 TS 的 OnPlayEmotion: 108 → 118 (转发给除发送者之外的人) 是另一版本语义;
//     EN v18 客户端没有表情走 108 的发包点, 故 EN_18_recv.yaml 的 PlayEmotionReq 已注释。
//   * 引爆判定与广播全在 Room.onObjectHit: 命中格无水泡 / 重复上报会被跳过,
//     本 handler 只负责解析并转发参数, 不直接发包。
val ObjectHitReq = PKTHandler { c ->
    if (readableBytes() < 6) {
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val targetObjectId = Decode2
    val gridX = Decode1
    val gridY = Decode1
    val delayMs = Decode2

    // 仅飞镖命中路径成立: 飞镖速度 1200px/s, 40px = 1 格
    val estimatedRangePx = delayMs * 1000 / 1200
    val estimatedRangeGrids = estimatedRangePx / 40

    if (debugMode) {
        println(
            "[op108] hit: objId=$targetObjectId grid=($gridX,$gridY) delay=${delayMs}ms " +
                "~range=${estimatedRangePx}px/${estimatedRangeGrids}grid"
        )
    }

    // 命中格有水泡 → Room 延时 delayMs 广播 S→C op138 state=0 引爆 (含发射者本人);
    // 命中箱子/角色/重复上报 → Room 内部跳过。objId 只用于日志诊断, 不参与查泡。
    Room.onObjectHit(targetObjectId, gridX, gridY, delayMs, sender = c)

    // 防御式消费: 未来客户端追加字段时不影响下一包解析。
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
