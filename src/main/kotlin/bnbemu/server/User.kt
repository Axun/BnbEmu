package bnbemu.server

import bnbemu.netty.ClientSocket
import java.util.concurrent.atomic.AtomicInteger

/** 模拟器暂无账号体系时的默认金币 (Lucci); 所有金币同步统一取此值, 避免各处硬编码漂移。 */
const val DEFAULT_LUCCI = 1234567

/**
 * 点券 (NX Cash) 的模拟器侧保守上限。
 * 客户端 op 201 (sub_5CFE29 -> sub_6E4AFE) 写 Cash 数组时**没有**上限校验
 * (只有 op 122 的 sub_6E4E23 会 min(value, sub_5062E9)), 这里仍沿用后者给出的
 * 3,000,000 作为封顶, 避免无限累加导致数值/显示异常。
 */
const val MAX_CASH = 3_000_000

/**
 * 点券 (NX Cash) 钱包。
 *
 * 与 Lucci 不同, 点券需要可变余额 (充值/消费), 且进频道/进商城会各自新开连线,
 * `ClientSocket.users` 只属于单条连线 —— 因此模拟器在无账号体系阶段用进程级全局钱包保存,
 * 保证充值后换连线、重进商城余额仍在。将来接入账号后把这里的读写换成按 userId 查库即可。
 */
object PlayerWallet {
    private val balance = AtomicInteger(0)

    /** 当前点券余额 */
    val cash: Int get() = balance.get()

    /** 充值, 返回充值后的余额 (与客户端一致地封顶 [MAX_CASH]) */
    fun charge(amount: Int): Int = balance.updateAndGet { minOf(it + amount, MAX_CASH) }
}

/**
 * 当前连线的金币余额。
 * 进频道/进商城可能各自新开连线, 此时 `c.users` 为空, 回落 [DEFAULT_LUCCI]。
 * 客户端金币显示统一读 CUserDynamicInfo 单例 (CA.exe 0x993E70 / Get(0) 0x6E4E0D),
 * 由 S->C op 122 UserDynamicInfo 写入 (0x4F80D0 -> Set 0x6E4E23)。
 */
val ClientSocket.lucci: Int
    get() = users.firstOrNull()?.lucci ?: DEFAULT_LUCCI

/** 当前连线的点券 (NX Cash) 余额, 取自进程级钱包 [PlayerWallet]。 */
val ClientSocket.cash: Int
    get() = PlayerWallet.cash

class User(
    val c: ClientSocket,
    val id: Int,
    val name: String,
    val level: Int = 5,
    val lucci: Int = DEFAULT_LUCCI,
    val exp: Int = 0,
    val win: Int = 0,
    val draw: Int = 0,
    val lose: Int = 0,
    val motto: String = "PopTag Forever!",
    val favorite: String = "Bazzi"
)