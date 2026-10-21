package io.legado.app.utils.eink

import android.util.Log
import splitties.init.appCtx

/**
 * 墨水屏全刷控制器
 *
 * 针对小米阅读器(RK3566)等 Rockchip 平台设备:
 *   系统隐藏服务 android.os.EinkManager.sendOneFullFrame() 触发全屏刷新
 *
 * Onyx Boox(高通)等设备无此服务, 静默降级不做任何事。
 * 反射句柄只在第一次成功后缓存, 失败也不重试(避免每页都抛异常)。
 */
object EinkRefresher {

    private const val TAG = "EinkRefresher"

    private var pageCount = 0

    private var sendOneFullFrame: java.lang.reflect.Method? = null
    private var einkManager: Any? = null
    private var resolved = false

    /**
     * 翻页时调用, 每 interval 页全刷一次
     * @param interval 间隔页数, <=0 禁用
     */
    fun onPageTurned(interval: Int) {
        if (interval <= 0) {
            pageCount = 0
            return
        }
        pageCount++
        if (pageCount >= interval) {
            pageCount = 0
            refreshNow()
        }
    }

    /**
     * 立即全屏刷新
     */
    fun refreshNow() {
        if (!resolved) {
            resolve()
        }
        try {
            val method = sendOneFullFrame
            if (method != null && einkManager != null) {
                method.invoke(einkManager)
                Log.d(TAG, "full refresh sent")
            }
        } catch (e: Exception) {
            Log.w(TAG, "full refresh failed: ${e.message}")
        }
    }

    private fun resolve() {
        resolved = true
        try {
            val cls = Class.forName("android.os.EinkManager")
            einkManager = appCtx.getSystemService("eink")
            sendOneFullFrame = cls.getDeclaredMethod("sendOneFullFrame")
            Log.i(TAG, "EinkManager resolved")
        } catch (e: Exception) {
            Log.i(TAG, "EinkManager not available, full refresh disabled")
        }
    }
}
