package io.legado.app.lib.ech

import android.os.Build
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.DebugLog
import splitties.init.appCtx
import java.io.File
import java.io.IOException

/**
 * ECH 本地代理管理器
 *
 * 从 assets 中提取 Go 编译的 ech-proxy 二进制，在应用私有目录运行。
 * 代理监听本地端口，内部做 DoH 查询 + ECH TLS 握手。
 * OkHttp 通过 HTTP 代理将请求转发到这个代理。
 */
object EchProxyManager {

    private const val TAG = "EchProxyManager"
    private const val PROXY_PORT = 17171
    private const val BINARY_NAME = "ech-proxy"

    private var process: Process? = null
    private var proxyBinary: File? = null
    private var shutdownHookRegistered = false

    val proxyHost: String get() = "127.0.0.1"
    val proxyPort: Int get() = PROXY_PORT

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * 启动 ECH 代理进程
     * @return true 如果启动成功
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) {
            DebugLog.d(TAG, "ECH proxy already running")
            return true
        }
        // 清理可能残留的旧进程
        stop()

        // 1. 提取二进制文件
        val binary = setupBinary()
        if (binary == null) {
            AppLog.put("ECH proxy: failed to setup binary")
            return false
        }
        proxyBinary = binary

        // 2. 启动进程
        val dohUrl = AppConfig.echDohUrl
        val cmd = arrayOf(binary.absolutePath, PROXY_PORT.toString(), dohUrl)

        DebugLog.d(TAG, "Starting ECH proxy: port=$PROXY_PORT doh=$dohUrl")

        return try {
            val pb = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .directory(appCtx.filesDir)
            // 清除可能影响子进程的代理环境变量，避免循环
            pb.environment().apply {
                remove("http.proxyHost")
                remove("http.proxyPort")
                remove("https.proxyHost")
                remove("https.proxyPort")
            }
            process = pb.start()

            // 读取进程输出（防止管道阻塞）
            Thread({
                process?.inputStream?.bufferedReader()?.use { reader ->
                    while (true) {
                        val line = runCatching { reader.readLine() }.getOrNull() ?: break
                        DebugLog.d(TAG, "proxy: $line")
                    }
                }
            }, "ech-proxy-logger").apply {
                isDaemon = true
                start()
            }

            // 等待短暂时间确认进程存活
            Thread.sleep(300)
            if (isRunning) {
                DebugLog.d(TAG, "ECH proxy started successfully on 127.0.0.1:$PROXY_PORT")
                // 注册 shutdown hook，应用退出时停止代理进程
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(Thread({
                        stop()
                    }, "ech-proxy-shutdown"))
                    shutdownHookRegistered = true
                }
                true
            } else {
                AppLog.put("ECH proxy: process exited immediately")
                false
            }
        } catch (e: IOException) {
            AppLog.put("ECH proxy: failed to start", e)
            false
        }
    }

    /**
     * 停止 ECH 代理进程
     */
    @Synchronized
    fun stop() {
        process?.let { p ->
            DebugLog.d(TAG, "Stopping ECH proxy")
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
    }

    /**
     * 从 assets 提取对应架构的二进制文件到应用私有目录
     */
    private fun setupBinary(): File? {
        val abi = pickAbi() ?: run {
            AppLog.put("ECH proxy: unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            return null
        }

        val assetName = "$BINARY_NAME-$abi"
        val outFile = File(appCtx.filesDir, BINARY_NAME)

        // 检查是否已存在且大小匹配
        try {
            val assetSize = appCtx.assets.open("ech-proxy/$assetName").use { it.available() }
            if (outFile.exists() && outFile.length() == assetSize.toLong() && outFile.canExecute()) {
                DebugLog.d(TAG, "ECH proxy binary already exists, skipping extraction")
                return outFile
            }
        } catch (e: IOException) {
            // asset 不存在或读取失败
            AppLog.put("ECH proxy: binary '$assetName' not found in assets", e)
            return null
        }

        // 复制二进制文件
        try {
            appCtx.assets.open("ech-proxy/$assetName").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // 设置可执行权限
            outFile.setExecutable(true, true)
            DebugLog.d(TAG, "Extracted ECH proxy binary: ${outFile.absolutePath} (${outFile.length()} bytes)")
            return outFile
        } catch (e: IOException) {
            AppLog.put("ECH proxy: failed to extract binary", e)
            return null
        }
    }

    /**
     * 根据设备 ABI 选择对应的二进制
     */
    private fun pickAbi(): String? {
        val abis = Build.SUPPORTED_ABIS
        for (abi in abis) {
            when (abi) {
                "arm64-v8a" -> return "arm64-v8a"
                "armeabi-v7a", "armeabi" -> return "armeabi-v7a"
                "x86_64" -> return "x86_64"
            }
        }
        return null
    }
}
