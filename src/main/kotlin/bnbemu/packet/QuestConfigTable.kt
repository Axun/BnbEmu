package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

/**
 * 任务配置条目 (对应 CA.exe CQuestConfigTable 0x006ABDE7 单项)
 *
 * @param questId 任务编号 (u32 LE, Encode4)
 * @param questName 任务标识/名称 (u16 LE 长度 + ASCII 字符串, Encode2 + string)
 * @param status 任务状态标志 (u8, Encode1)
 */
data class QuestConfigEntry(
    val questId: Int,
    val questName: String,
    val status: Int = 0,
) {
    fun encode(p: SendPacketBase) {
        p.Encode4(questId)
        val nameBytes = if (questName.isEmpty()) ByteArray(0) else encodeName(questName)
        p.Encode2(nameBytes.size)
        if (nameBytes.isNotEmpty()) {
            p.EncodeBuffer(nameBytes)
        }
        p.Encode1(status)
    }
}

/**
 * S→C 任务配置表响应 (Opcode 78, S→C)
 *
 * 逆向确认 (CA.exe, 2026-11-01):
 * 1. 客户端处理入口: CSelectServerStage::OnPacket (0x00502E73 -> 0x004F800E)
 * 2. 内部由 CFxContext 的 CQuestConfigTable (0x006ABDE7, RTTI: .?AVCQuestConfigTable@@, 虚表 0x008BA878) 解析:
 *    [Encode4(count)] + N × [Encode4(questId) + Encode2(nameLen) + nameBytes + Encode1(status)]
 * 3. 当 count <= 0 时客户端直接返回，默认无任务时发送 Encode4(0)
 * 4. 关键连带效应: 客户端处理完该包后，在 0x004F8072 主动构造并发送 op 81 (JoinSessionReq)，
 *    从而触发大厅握手推送流程。
 */
fun QuestConfigTable(quests: List<QuestConfigEntry> = emptyList()) = PKT {
    Encode4(quests.size)
    for (q in quests) {
        q.encode(this)
    }
}

// 兼容别名
fun Unk78Result() = QuestConfigTable()
