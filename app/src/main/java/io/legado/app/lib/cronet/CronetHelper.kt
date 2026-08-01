@file:Keep
@file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.customIp
import io.legado.app.utils.DebugLog
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.X509Util
import org.json.JSONObject
import splitties.init.appCtx

internal const val BUFFER_SIZE = 32 * 1024

val cronetEngine: ExperimentalCronetEngine? by lazy {
    CronetLoader.preDownload()
    disableCertificateVerify()
    val builder = ExperimentalCronetEngine.Builder(appCtx).apply {
        if (CronetLoader.install()) {
            setLibraryLoader(CronetLoader)//设置自定义so库加载
        }
        setStoragePath(appCtx.externalCache.absolutePath)//设置缓存路径
        enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())//设置50M的磁盘缓存
        enableQuic(true)//设置支持http/3
        enableHttp2(true)  //设置支持http/2
        enablePublicKeyPinningBypassForLocalTrustAnchors(true)
        enableBrotli(true)//Brotli压缩
        setExperimentalOptions(options)
    }
    try {
        val engine = builder.build()
        DebugLog.d("Cronet Version:", engine.versionString)
        return@lazy engine
    } catch (e: Throwable) {
        AppLog.put("初始化cronetEngine出错", e)
        return@lazy null
    }
}

val options by lazy {
    val options = JSONObject()

    //设置域名映射规则
    //MAP hostname ip,MAP hostname ip
//    val host = JSONObject()
//    host.put("host_resolver_rules","")
//    options.put("HostResolverRules", host)

    //启用DnsHttpsSvcb更容易迁移到http3
    val dnsSvcb = JSONObject()
    dnsSvcb.put("enable", true)
    dnsSvcb.put("enable_insecure", true)
    dnsSvcb.put("use_alpn", true)
    options.put("UseDnsHttpsSvcb", dnsSvcb)

    options.put("AsyncDNS", JSONObject("{'enable':true}"))

    // ECH 现在通过本地 Go 代理实现 (EchProxyManager)
    // ECH 开启时不使用 Cronet (CronetInterceptor 会跳过)
    // 所以这里不再配置 Cronet 的 DoH/ECH

    options.toString()
}

fun buildRequest(request: Request, callback: UrlRequest.Callback): UrlRequest? {
    val url = request.url.toString()
    val headers: Headers = request.headers
    val requestBody = request.body
    return cronetEngine?.newUrlRequestBuilder(
        customHost(url),
        callback,
        okHttpClient.dispatcher.executorService
    )?.apply {
        setHttpMethod(request.method)//设置
        allowDirectExecutor()
        headers.forEachIndexed { index, _ ->
            if (headers.name(index) == cookieJarHeader) return@forEachIndexed
            addHeader(headers.name(index), headers.value(index))
        }
        if (requestBody != null) {
            val contentType: MediaType? = requestBody.contentType()
            if (contentType != null) {
                addHeader("Content-Type", contentType.toString())
            } else {
                addHeader("Content-Type", "text/plain")
            }
            val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
            } else {
                BodyUploadProvider(requestBody)
            }
            provider.use {
                this.setUploadDataProvider(it, okHttpClient.dispatcher.executorService)
            }
        }
    }?.build()
}

private fun customHost(url: String): String {
    // ECH 启用时跳过域名→IP 替换，否则 SNI 会变成 IP，ECH 无法工作
    if (AppConfig.isECH) return url
    val urlIp = customIp.remove(url)
    if (AppConfig.hostMap.isEmpty() && urlIp == null) return url
    val host = AppPattern.domainRegex.find(url)?.groupValues?.getOrNull(1) ?: return url
    if (urlIp != null) return url.replaceFirst(host, urlIp)
    val ip = when (val configIps = AppConfig.hostMap[host]) {
        is String -> configIps.splitToSequence(',')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
        is List<*> -> configIps.firstOrNull()
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.trim()
        else -> null
    } ?: return url
    return url.replaceFirst(host, ip)
}

private fun disableCertificateVerify() {
    runCatching {
        val sDefaultTrustManager = X509Util::class.java.getDeclaredField("sDefaultTrustManager")
        sDefaultTrustManager.isAccessible = true
        sDefaultTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
    runCatching {
        val sTestTrustManager = X509Util::class.java.getDeclaredField("sTestTrustManager")
        sTestTrustManager.isAccessible = true
        sTestTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
}
