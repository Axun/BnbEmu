package bnbemu

import bnbemu.domain.DEFAULT_LEVEL
import bnbemu.domain.DEFAULT_LUCCI
import bnbemu.server.BattleConfig
import com.charleskorn.kaml.EmptyYamlDocumentException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.decodeFromStream
import org.tinylog.kotlin.Logger
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

const val locale = "EN"

const val Ver = 18

val ACP: Charset = Charset.forName("windows-1252")

val debugMode: Boolean
    get() = ServerConfig.current.debugMode

val showDecValue: Boolean
    get() = ServerConfig.current.showDecValue

inline val tickCount
    get() = System.currentTimeMillis().toInt()

data class DatabaseConfig(
    val path: String = "./bnb.db",
)

data class PlayerDefaultsConfig(
    val defaultLucci: Int = DEFAULT_LUCCI,
    val defaultCash: Int = 0,
    val defaultLevel: Int = DEFAULT_LEVEL,
)

data class AppConfig(
    val debugMode: Boolean = true,
    val showDecValue: Boolean = false,
    val database: DatabaseConfig = DatabaseConfig(),
    val player: PlayerDefaultsConfig = PlayerDefaultsConfig(),
    val battle: BattleConfig = BattleConfig(),
)

/**
 * 单机服务端配置管理器：从工作目录 `Config.yaml` 加载配置，缺省字段或空文件自动回落默认值。
 */
object ServerConfig {
    private val defaultPath: Path = Path.of("Config.yaml")

    @Volatile
    var current: AppConfig = loadFromFile(defaultPath, logResult = false)
        private set

    fun load(path: Path = defaultPath): AppConfig {
        val loaded = loadFromFile(path, logResult = true)
        current = loaded
        return loaded
    }

    fun loadFromYaml(yamlText: String): AppConfig {
        val loaded = parseYaml(yamlText, sourceName = "inline")
        current = loaded
        return loaded
    }

    fun resetToDefaults(): AppConfig {
        val defaults = AppConfig()
        current = defaults
        return defaults
    }

    private fun loadFromFile(path: Path, logResult: Boolean): AppConfig {
        if (!Files.isRegularFile(path)) {
            if (logResult) {
                Logger.info("[Config] 未找到 ${path.toAbsolutePath()}，使用默认单机配置")
            }
            return AppConfig()
        }
        return runCatching {
            val text = Files.readString(path)
            parseYaml(text, sourceName = path.toString()).also { cfg ->
                if (logResult) {
                    Logger.info(
                        "[Config] 已加载 $path | debugMode=${cfg.debugMode} showDecValue=${cfg.showDecValue} " +
                            "db=${cfg.database.path} defaultLucci=${cfg.player.defaultLucci} " +
                            "defaultLevel=${cfg.player.defaultLevel}"
                    )
                }
            }
        }.getOrElse { e ->
            Logger.warn("[Config] 解析 $path 失败，回落默认配置: ${e.message}")
            AppConfig()
        }
    }

    internal fun parseYaml(text: String, sourceName: String = "Config.yaml"): AppConfig {
        if (text.isBlank()) return AppConfig()
        val node = try {
            Yaml.default.parseToYamlNode(text)
        } catch (_: EmptyYamlDocumentException) {
            return AppConfig()
        }
        if (node is YamlNull) return AppConfig()
        val root = node as? YamlMap ?: run {
            Logger.warn("[Config] $sourceName 根节点不是 YAML Map，回落默认配置")
            return AppConfig()
        }

        val defaults = AppConfig()
        val dbNode = root.childMap("database")
        val playerNode = root.childMap("player")
        val battleNode = root.childMap("battle")

        return AppConfig(
            debugMode = root.bool("debugMode", defaults.debugMode, sourceName),
            showDecValue = root.bool("showDecValue", defaults.showDecValue, sourceName),
            database = DatabaseConfig(
                path = dbNode.str("path", defaults.database.path, sourceName),
            ),
            player = PlayerDefaultsConfig(
                defaultLucci = playerNode.int("defaultLucci", defaults.player.defaultLucci, sourceName),
                defaultCash = playerNode.int("defaultCash", defaults.player.defaultCash, sourceName),
                defaultLevel = playerNode.int("defaultLevel", defaults.player.defaultLevel, sourceName),
            ),
            battle = parseBattleConfig(battleNode, defaults.battle, sourceName),
        )
    }

    private fun parseBattleConfig(map: YamlMap?, defaults: BattleConfig, sourceName: String): BattleConfig =
        BattleConfig(
            tickIntervalMs = map.long("tickIntervalMs", defaults.tickIntervalMs, sourceName),
            matchTimeoutMs = map.long("matchTimeoutMs", defaults.matchTimeoutMs, sourceName),
            gameOverDelayMs = map.long("gameOverDelayMs", defaults.gameOverDelayMs, sourceName),
            resultReturnDelayMs = map.long("resultReturnDelayMs", defaults.resultReturnDelayMs, sourceName),
            reenterRoomAfterBack = map.bool("reenterRoomAfterBack", defaults.reenterRoomAfterBack, sourceName),
            launchedBombActivationDelayMs = map.long(
                "launchedBombActivationDelayMs",
                defaults.launchedBombActivationDelayMs,
                sourceName,
            ),
            movableBoxDedupeWindowMs = map.long(
                "movableBoxDedupeWindowMs",
                defaults.movableBoxDedupeWindowMs,
                sourceName,
            ),
            bombMoveDedupeWindowMs = map.long(
                "bombMoveDedupeWindowMs",
                defaults.bombMoveDedupeWindowMs,
                sourceName,
            ),
            dartDetonationMaxDelayMs = map.long(
                "dartDetonationMaxDelayMs",
                defaults.dartDetonationMaxDelayMs,
                sourceName,
            ),
            winExpAfter = map.int("winExpAfter", defaults.winExpAfter, sourceName),
            loseExpAfter = map.int("loseExpAfter", defaults.loseExpAfter, sourceName),
            drawExpAfter = map.int("drawExpAfter", defaults.drawExpAfter, sourceName),
            winLucci = map.int("winLucci", defaults.winLucci, sourceName),
            loseLucci = map.int("loseLucci", defaults.loseLucci, sourceName),
            drawLucci = map.int("drawLucci", defaults.drawLucci, sourceName),
        )

    private fun YamlMap?.childMap(key: String): YamlMap? {
        val node = this?.entries?.entries?.firstOrNull { it.key.content == key }?.value ?: return null
        if (node is YamlNull) return null
        return node as? YamlMap
    }

    private fun YamlMap?.scalar(key: String): String? {
        val node = this?.entries?.entries?.firstOrNull { it.key.content == key }?.value ?: return null
        if (node is YamlNull) return null
        return (node as? YamlScalar)?.content
    }

    private fun YamlMap?.bool(key: String, default: Boolean, sourceName: String): Boolean {
        val raw = scalar(key) ?: return default
        return when (raw.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> {
                Logger.warn("[Config] $sourceName 字段 $key='$raw' 不是有效布尔值，回落默认 $default")
                default
            }
        }
    }

    private fun YamlMap?.int(key: String, default: Int, sourceName: String): Int {
        val raw = scalar(key) ?: return default
        return raw.trim().toIntOrNull() ?: run {
            Logger.warn("[Config] $sourceName 字段 $key='$raw' 不是有效整数，回落默认 $default")
            default
        }
    }

    private fun YamlMap?.long(key: String, default: Long, sourceName: String): Long {
        val raw = scalar(key) ?: return default
        return raw.trim().toLongOrNull() ?: run {
            Logger.warn("[Config] $sourceName 字段 $key='$raw' 不是有效长整数，回落默认 $default")
            default
        }
    }

    private fun YamlMap?.str(key: String, default: String, sourceName: String): String {
        val raw = scalar(key) ?: return default
        return raw.ifBlank { default }
    }
}

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
