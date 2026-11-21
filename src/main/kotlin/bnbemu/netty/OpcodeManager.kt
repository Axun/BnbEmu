package bnbemu.netty

import io.github.classgraph.ClassGraph
import bnbemu.loadYamlResource
import org.tinylog.kotlin.Logger
import java.util.IdentityHashMap

object OpcodeManager {
    val recvOps: Map<Int, String>
    private val recvOpNames = arrayOfNulls<String>(handlerArray.size)
    // 只读访问
    private val sendOpMap = IdentityHashMap<Class<*>, Int>(1000)

    init {
        //  載入 YAML
        val recvYaml: Map<String, Int> = loadYamlResource("opcode_recv")
        val sendYaml: Map<String, Int> = loadYamlResource("opcode_send")

        val recvMap = HashMap<Int, String>(recvYaml.size)
        for ((name, op) in recvYaml) {
            if (op !in handlerArray.indices) {
                Logger.warn("opcode_recv.yaml 中 $name=$op 超出 handlerArray 範圍 (0..${handlerArray.lastIndex})，將被忽略")
                continue
            }
            val prev = recvMap.put(op, name)
            if (prev != null && prev != name) {
                Logger.warn("opcode_recv.yaml 存在重複收包 opcode $op: $prev 被 $name 覆蓋")
            }
            recvOpNames[op] = name
        }
        recvOps = recvMap

        val pktInterface = PKT::class.java.name
        val handlerType = PKTHandler::class.java.name

        // 掃描
        ClassGraph()
            .enableFieldInfo()
            .ignoreFieldVisibility()
            .acceptPackages("bnbemu.handler", "bnbemu.packet")
            .scan().use { scanResult -> // use 區塊自動關閉資源

                scanResult.allClasses.forEach { classInfo ->
                    // 處理 Handler (接收) ---
                    classInfo.fieldInfo
                        .filter { it.typeSignatureOrTypeDescriptor.toString() == handlerType }
                        .forEach { fieldInfo ->
                            val op = recvYaml[fieldInfo.name]
                            if (op == null) {
                                Logger.warn("Handler ${classInfo.name}.${fieldInfo.name} 未在 opcode_recv.yaml 中定義，未綁定")
                            } else if (op in handlerArray.indices) {
                                val field = fieldInfo.loadClassAndGetField().apply { isAccessible = true }
                                val handler = field.get(null) as? PKTHandler
                                val existing = handlerArray[op]
                                if (existing != null && existing !== handler) {
                                    error("Opcode $op (${fieldInfo.name}) 衝突! 已綁定 Handler 被 ${classInfo.name}.${fieldInfo.name} 重複定義/覆蓋")
                                }
                                handlerArray[op] = handler
                            }
                        }

                    // 處理 Packet (發送) ---將所有 Packet Class 對應到 Opcode
                    if (classInfo.implementsInterface(pktInterface)) {
                        val clazz = classInfo.loadClass()
                        val filteredName = clazz.name.substringAfter('$').substringBefore('$').substringBefore('_')
                        val op = sendYaml[filteredName]
                        if (op != null) {
                            sendOpMap[clazz] = op
                        } else {
                            Logger.warn("發送包 ${clazz.name} (解析鍵 '$filteredName') 未在 opcode_send.yaml 中定義，未註冊")
                        }
                    }
                }
            }

        Logger.info("初始化完成: 綁定 ${handlerArray.count { it != null }} 個 Handler, 註冊 ${sendOpMap.size} 個發送包")
    }

    /** 顯式觸發初始化，避免首個連線在 Netty I/O 執行緒上執行類路徑掃描。 */
    fun init() {}

    fun getRecvOpName(opcode: Int): String? = recvOpNames.getOrNull(opcode)

    // 獲取 Opcode (含快取)
    fun getSendOp(clazz: Class<*>): Int = sendOpMap[clazz] ?: -1
}