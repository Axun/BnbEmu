package pingu.handler.room

import pingu.netty.PKTHandler

/**
 * C->S op 151: 幽灵/局内槽位状态上报请求 (GenerateGhostReq)
 * 客户端在槽位状态变动时可能上报，服务端静默消费，避免找不到 Handler 警告。
 */
val GenerateGhostReq = PKTHandler { c ->
    // 静默消费
}
