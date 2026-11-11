package bnbemu.constants

/**
 * 会话槽位上限 —— 真端 MAX_SESSION_SLOTS（reference/古早真端ts实现/src/FXGS/Defs.ts:109）。
 *
 * 房间恒为 8 槽；地图的 maxPlayer 可以小于它（4 人图 / 1 人的广场图），
 * 超过它的地图人数一律按 8 截断（GameSession.ts:1384 LaunchGameStage）。
 */
const val MAX_SESSION_SLOTS = 8
