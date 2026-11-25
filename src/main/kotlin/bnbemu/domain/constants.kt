package bnbemu.domain

// 早期角色是美服官方命名，由于关服早，后面的角色直接用韩服名
enum class CharacterType(val value: Int) {
    // 皮蛋
    Dao(0),
    // 黑妞
    Diz(1),
    // 小强
    Moss(2),
    // 葱头
    Melvin(3),
    // 蓝蓝
    Marid(4),
    // 宝宝
    Boz(5),
    // 小乖
    Ike(6),
    // 胖墩
    Plunk(7),
    // 月野兔
    Daltokki(8),
    // 小忽
    Su(9),
    // 蓝妹妹。又叫Luxury Marid
    PoshEvie(10),
    // 墩小爷
    LuxuryKephi(11),
    // 超级小乖
    SuperUni(12),
    SuperUni1(13),
    // 皮蛋超人
    SuperDao(14),
    // 超级月野兔
    SuperDaltokki(15),
    // 超级小强
    SuperMoss(17),
    // 洋葱妹妹
    Erini(16),
    // 超级黑妞
    SuperDiz(18),
    // 雷
    Ray(19),
    // 超级小忽
    SuperSu(25),
    // 露西
    Lucy(26),
    // 超级雷
    SuperRay(27),
    // 超级露西
    SuperLucy(28),
    // 超级洋葱妹妹
    SuperErini(29)
}

/**
 * 会话槽位上限 —— 真端 MAX_SESSION_SLOTS（reference/古早真端ts实现/src/FXGS/Defs.ts:109）。
 *
 * 房间恒为 8 槽；地图的 maxPlayer 可以小于它（4 人图 / 1 人的广场图），
 * 超过它的地图人数一律按 8 截断（GameSession.ts:1384 LaunchGameStage）。
 */
const val MAX_SESSION_SLOTS = 8

/**
 * op 60 ItemDef 的物品分类（客户端 `ItemDef.category`，u8）。
 *
 * 分类决定了三件事，所以各处不要写裸数字：
 *  1. **装备大类**（`sub_67884E`）→ 复合槽位 = 24 × 大类 + 子槽，见 [InventoryRules.equipGroupOfCategory]；
 *  2. **op 100 SetPlayer 的三段装扮清单**走哪一段，见 `packet/room/SetPlayer.kt`；
 *  3. 是否可以堆叠 / 是否是消耗品，见 [InventoryRules.isStackableCategory]。
 *
 * 只列本模拟器实际用到、且已在基础表（`data/item_def_base.bin`）或客户端分支里验证过的分类；
 * 其余（1/2/9/20/33/36/41/42）只在 [InventoryRules.equipGroupOfCategory] 的映射表里出现。
 */
object ItemCategory {
    /** 3 = 角色表情/动作模型表（Dao/Bazzi…，op 60 里按当前角色重写的那些条目） */
    const val CHARACTER_MODEL = 3

    /** 4 = 胜利/庆祝动画（`Item/Fanfare###`） */
    const val FANFARE = 4

    /** 10 = 装扮背景 / 旗帜 / 边框（`Item/ShopDeco`，大类 9，槽位 216~239） */
    const val SLOT_DECO = 10

    /** 12 = 角色特效（`Item/CharDeco`，大类 10，走 op 100 的 count2） */
    const val CHAR_DECO = 12

    /** 13 = 道具袋 / 背包等级卡（客户端不可穿戴：`sub_67884E(13) == 0`） */
    const val ITEM_BAG = 13

    /** 14 = 水泡皮肤（`Item/Bomb`，大类 10，但 op 100 只认尾部 f32 = `param`） */
    const val BOMB = 14

    /** 15 = 喇叭等消耗品（`Item/ItemSpeaker`；基础角色卡 `itemId=1 Dao` 也落这类，不可穿戴） */
    const val SPEAKER = 15

    /** 16 = 进阶角色卡（如 2082 蓝妹妹，大类 6，槽位 144~167） */
    const val ADVANCE_CARD = 16

    /** 18 = 名牌背景 ID deco（`Icon/ItemIDdeco_C###`，大类 9；`skinId` 即 `IDDeco/IDDeco_C%03d` 序号） */
    const val ID_DECO = 18

    /** 19 = 纸娃娃（`CGOAvatar`，大类 5，走 op 100 的 count3，要求 `param > 0`） */
    const val AVATAR = 19

    /** 29 = 天梯段位装饰（`LadderDeco/LadderDeco_*`，大类 9） */
    const val LADDER_DECO = 29

    /** 31 = 情侣戒指（`Item/ItemCoupleRing##`） */
    const val COUPLE_RING = 31
}