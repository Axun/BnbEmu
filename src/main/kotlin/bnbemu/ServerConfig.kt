package bnbemu

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private val confMap: Map<String, String> = loadYaml("Config")

val locale = Region.valueOf(confMap["Locale"] ?: error("Config.yaml 缺少 Locale 設定"))

val Ver by lazy {
    confMap["${locale}_VER"]?.toInt()
        ?: error("找不到 ${locale} 的版本號 (XX_VER)")
}

val ACP: Charset = when (locale) {
    Region.JP -> Charset.forName("MS932")
    Region.CN -> Charset.forName("MS936")
    Region.TW -> Charset.forName("MS950")
    Region.EN -> Charset.forName("windows-1252")
    else -> StandardCharsets.UTF_8
}

val isJP = locale == Region.JP
val isTW = locale == Region.TW
val isCN = locale == Region.CN
val isEN = locale == Region.EN

val debugMode = true
val showDecValue = false

inline val tickCount
    get() = System.currentTimeMillis().toInt()

enum class Region {
    JP, TW, CN, EN
}

inline fun <reified T> loadYaml(fileName: String): T {
    return File(fileName + ".yaml").inputStream().use {
        Yaml.default.decodeFromStream(it)
    }
}