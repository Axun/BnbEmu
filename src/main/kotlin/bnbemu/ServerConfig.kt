package bnbemu

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset

const val locale = "EN"

const val Ver = 18

val ACP: Charset = Charset.forName("windows-1252")

val debugMode = true
val showDecValue = false

inline val tickCount
    get() = System.currentTimeMillis().toInt()

private object YamlResourceAnchor

@PublishedApi
internal fun openYamlStream(fileName: String): InputStream {
    val cleanName = fileName.removePrefix("classpath:").trimStart('/')
    val resourcePath = if (cleanName.endsWith(".yaml")) cleanName else "$cleanName.yaml"
    return YamlResourceAnchor::class.java.getResourceAsStream("/$resourcePath")
        ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
        ?: throw FileNotFoundException("找不到 classpath 资源: /$resourcePath")
}

inline fun <reified T> loadYamlResource(fileName: String): T {
    return openYamlStream(fileName).use {
        Yaml.default.decodeFromStream(it)
    }
}