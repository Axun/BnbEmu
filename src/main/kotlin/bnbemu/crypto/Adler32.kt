package bnbemu.crypto

/**
 * Adler-32 校验和工具类。
 *
 * 针对 PopTag / CA.exe 客户端定制：
 * 客户端内部校验（如 sub_74EE50 与 sub_78BB60）采用初始值 a=0, b=0 的 Adler-32 变体
 * （标准 zlib Adler-32 的 a 初始值为 1），模数为 65521。
 */
object Adler32 {
    const val MOD: Int = 65521
    private const val SSD_BLOCK_MARKER: Int = 0x18

    /**
     * 计算字节数组切片的 Adler-32 (init=0) 校验和 (对应 CA.exe 0x0074EE50 sub_74EE50)。
     *
     * @param data 数据源
     * @param offset 起始偏移量
     * @param length 计算长度
     * @return 32 位校验和: (b shl 16) or a
     */
    fun computeZero(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var a = 0
        var b = 0
        for (i in offset until (offset + length)) {
            a = (a + (data[i].toInt() and 0xFF)) % MOD
            b = (b + a) % MOD
        }
        return (b shl 16) or a
    }

    /**
     * 针对 SSD 数据块的校验和计算 (对应 CA.exe sub_78BB60)。
     *
     * SSD 块结构:
     * - `[0]`: 0x18 (BLOCK_MARKER)
     * - `[1..3]`: 24-bit LE 数据区长度 len
     * - `[4]`: 标志位 (未压缩通常为 0)
     * - `[5 .. 5+len-1]`: 数据载荷
     *
     * 校验从 offset 5 开始，长度为 len。
     */
    fun computeSsdBlock(block: ByteArray): Int {
        check(block.size >= 6 && (block[0].toInt() and 0xFF) == SSD_BLOCK_MARKER) {
            "非法 SSD 块: size=${block.size}, marker=${block.firstOrNull()?.let { it.toInt() and 0xFF }}"
        }
        val len = (block[1].toInt() and 0xFF) or
                ((block[2].toInt() and 0xFF) shl 8) or
                ((block[3].toInt() and 0xFF) shl 16)
        check(5 + len == block.size) { "SSD 块长度不符: header=$len, size=${block.size}" }
        return computeZero(block, 5, len)
    }
}
