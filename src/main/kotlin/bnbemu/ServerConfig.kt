package bnbemu

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private val confMap: Map<String, String> = loadYaml("Config")

val locale = "EN"

const val Ver = 18

val ACP: Charset = Charset.forName("windows-1252")

val isEN = true

val debugMode = true
val showDecValue = false

inline val tickCount
    get() = System.currentTimeMillis().toInt()


inline fun <reified T> loadYaml(fileName: String): T {
    return File(fileName + ".yaml").inputStream().use {
        Yaml.default.decodeFromStream(it)
    }
}