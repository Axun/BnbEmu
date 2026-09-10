package pingu.handler

import pingu.netty.Decode4
import pingu.netty.PKTHandler
import pingu.packet.ChannelsExtraInfo
import pingu.packet.ChannelsState
import pingu.packet.Unk12

/**
 * 字符串表/过滤词库版本修订号上报请求 (Opcode 55 / 0x37)
 *
 * 触发场景与时序:
 * - 客户端在登录凭据认证成功 (收完 op 4 AskFirstInfoResult) 后，在进入服务器/频道列表界面时由 CLoginStage::On... (0x622c44) / CLoginStageChina::On... (0x61df03) 触发;
 * - 调用 CMainSystem (0x68dbb6 -> 0x68d79e)，将客户端本地当前加载的 3 份 SSD 资料表 Revision / 校验和封装发送给服务端;
 *
 * 载荷结构 (共 12 字节 / 3 个 Encode4 整数):
 * - stringTableRev: E4(dword ptr [CStringMan::s_pInstance + 0x5c]) -> 字符串表版本/校验和 (抓包样本: 0x1fab3cf5 = 531315957)
 * - questStringRev: E4(dword ptr [CQuestStringMan::s_pInstance + 0x5c]) -> 任务字符串表版本/校验和 (抓包样本: 0x00000000 = 0)
 * - curseFilterRev: E4(CCurseFilter::s_pInstance->GetRevision()) -> 过滤词/脏话词库版本/校验和 (抓包样本: 0x380a0583 = 940180867)
 *
 * 服务端响应:
 * - 正常情况下连发频道列表相关推送 [11 ChannelsExtraInfo, 13 ChannelsState, 12 Unk12];
 * - 客户端据此显示频道(服务器)列表; 缺少它登录后无法进频道选择界面。
 */
val StringTableRevisionReq = PKTHandler { c ->
    val stringTableRev = Decode4
    val questStringRev = Decode4
    val curseFilterRev = Decode4

    c.send(
        ChannelsExtraInfo(),
        ChannelsState(),
        Unk12(),
    )
}
