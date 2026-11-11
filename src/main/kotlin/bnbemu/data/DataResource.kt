package bnbemu.data

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

// ============================================================================
// 资源文件唯一读取入口
// ============================================================================
// 读取链条
//   classpath:/<rel> → src/main/resources/<rel> → <rel> → <basename>
// 前两条覆盖「jar/target 运行」与「IDE 直接跑 main」，后两条保留「cwd 已是 resources
// 或数据文件被拷到工作目录」的历史用法（旧 FxdynMapInfo 依赖裸文件名兜底）。
//
// 约定：
//   · 缺失一律抛 IllegalStateException（fail-fast）。数据文件是唯一事实源，
//     静默降级只会把问题推迟到某次下发对不上字节时才暴露。
//   · 本对象只负责「拿到字节 / 文本」，不解析内容 —— 解码归各自的 *Info / *Catalog。
//   · jar 内无法列目录，需要枚举清单时只能依赖已知名字列表或显式 index 文件。
// ============================================================================

object DataResource {

    /** 开发态源码树根（IDE 里 cwd 通常是工程根）。 */
    private const val DEV_ROOT = "src/main/resources"

    /**
     * 读取资源全部字节。
     *
     * @param path 相对 resources 根的路径，用 `/` 分隔，如 "data/item_def_base.bin"；
     *             允许前导 `/`（classpath 写法），会被归一化掉。
     */
    fun bytes(path: String): ByteArray = open(path).use(InputStream::readAllBytes)

    /** 按文本读取，默认 UTF-8。 */
    fun text(path: String, charset: Charset = StandardCharsets.UTF_8): String =
        open(path).use { it.reader(charset).readText() }

    /**
     * 打开资源流，调用方负责关闭（[bytes] / [text] 已代为关闭）。
     *
     * @throws IllegalStateException 所有候选路径都不存在，异常信息含完整尝试列表。
     */
    fun open(path: String): InputStream {
        val rel = path.trimStart('/')
        require(rel.isNotEmpty()) { "资源路径为空" }

        val tried = mutableListOf<String>()
        DataResource::class.java.getResourceAsStream("/$rel")?.let { return it }
        tried += "classpath:/$rel"

        val candidates = listOf("$DEV_ROOT/$rel", rel, rel.substringAfterLast('/')).distinct()
        for (candidate in candidates) {
            val file = File(candidate)
            tried += file.path
            if (file.isFile) return file.inputStream()
        }

        throw IllegalStateException(
            "找不到资源 $rel（已按顺序尝试: ${tried.joinToString(" , ")}）"
        )
    }
}
