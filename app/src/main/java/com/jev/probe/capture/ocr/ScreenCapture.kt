package com.jev.probe.capture.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screenshot, taken through the accessibility service (no MediaProjection,
 * no root, no cast permission dialog). Requires `android:canTakeScreenshot="true"`
 * in the service config — and that flag only takes effect after the user turns
 * the accessibility service OFF and ON again.
 *
 * Contract: [capture] is called on the main thread and answers on the main
 * thread, exactly once, with either a software [Result.Ok] bitmap or a
 * [Result.Failed] carrying a sentence the overlay can show as-is.
 *
 * Three things here exist because the platform bites otherwise:
 * - The result arrives as a [android.hardware.HardwareBuffer]. It must be copied
 *   into an ARGB_8888 bitmap and closed immediately; leaking buffers starves the
 *   system compositor after a handful of shots.
 * - The system throttles screenshots (errorCode 3). We throttle ourselves first
 *   (>= 1s between attempts) and back off 1s -> 2s -> 4s ... 30s while failing,
 *   so a chat app whose tree reads empty on every content-changed event can
 *   never turn into a screenshot machine gun.
 * - Our own floating overlay is part of the display and would be baked into the
 *   picture, so the caller hands us hide/restore callbacks.
 */
class ScreenCapture(
    private val service: AccessibilityService,
    private val hideOverlay: () -> Unit = {},
    private val restoreOverlay: () -> Unit = {}
) {

    sealed class Result {
        /**
         * [scaleX]/[scaleY] = bitmap size / captured area size, and
         * [originX]/[originY] = where that area starts on screen.
         *
         * A window shot (API 34+) is NOT the whole display: in split screen, or
         * whenever the window excludes the status bar, the picture is both
         * smaller than the display and offset from its origin. So node rects map
         * as `bitmapX = (screenX - originX) * scaleX`, and OCR boxes map back the
         * other way. For the whole-display fallback the origin is (0,0) and the
         * size is the display's, which is the old behaviour.
         */
        data class Ok(
            val bitmap: Bitmap,
            val scaleX: Float,
            val scaleY: Float,
            val originX: Int = 0,
            val originY: Int = 0
        ) : Result()
        data class Failed(val code: Int, val humanMessage: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Take one screenshot. [onResult] runs on the main thread, exactly once. */
    fun capture(onResult: (Result) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val need = requiredInterval()
        if (now - lastAttemptAt < need) {
            onResult(Result.Failed(CODE_THROTTLED, "截屏太频繁"))
            return
        }
        lastAttemptAt = now

        val done = AtomicBoolean(false)
        val finish: (Result) -> Unit = { r ->
            if (done.compareAndSet(false, true)) {
                restoreOverlay()
                if (r is Result.Ok) failStreak = 0
                else failStreak = (failStreak + 1).coerceAtMost(MAX_STREAK)
                onResult(r)
            }
        }

        // Hide the bubble, give the compositor a frame to drop it, then shoot.
        runCatching { hideOverlay() }
        main.postDelayed({ shoot(finish, done) }, HIDE_SETTLE_MS)
    }

    private fun shoot(finish: (Result) -> Unit, done: AtomicBoolean) {
        val exec = service.mainExecutor
        // Which area the picture will cover. Set just before the window shot is
        // issued and read inside the callback, so the mapping always matches the
        // call that actually produced the bitmap.
        var windowBounds: Rect? = null
        // The system can simply never call back (seen when a shot lands on a
        // protected window during a transition). Without this the overlay stays
        // INVISIBLE and the caller's busy flag is stuck until the service dies.
        val timeout = Runnable { finish(Result.Failed(CODE_TIMEOUT, humanMessage(CODE_TIMEOUT))) }
        val cb = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                main.removeCallbacks(timeout)
                // Already timed out: this result is void. Drop the buffer (never
                // leak it) and do not touch the caller a second time.
                if (done.get()) { runCatching { result.hardwareBuffer.close() }; return }
                finish(toBitmap(result, windowBounds))
            }
            override fun onFailure(errorCode: Int) {
                main.removeCallbacks(timeout)
                finish(Result.Failed(errorCode, humanMessage(errorCode)))
            }
        }

        // API 34+: shooting just the active window is cheaper and is allowed on
        // some OEM builds that refuse a whole-display capture. Fall back to the
        // display shot when the window id is unknown or the call is unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val node = runCatching { service.rootInActiveWindow }.getOrNull()
            val windowId = node?.windowId
            if (windowId != null && windowId != -1) {
                windowBounds = runCatching {
                    val r = Rect()
                    node.window?.getBoundsInScreen(r)
                    r
                }.getOrNull()?.takeIf { it.width() > 0 && it.height() > 0 }
                try {
                    service.takeScreenshotOfWindow(windowId, exec, cb)
                    main.postDelayed(timeout, TIMEOUT_MS)
                    return
                } catch (e: Throwable) {
                    windowBounds = null
                    Log.w(TAG, "takeScreenshotOfWindow unavailable: ${e.javaClass.simpleName}")
                }
            }
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, exec, cb)
            main.postDelayed(timeout, TIMEOUT_MS)
        } catch (e: Throwable) {
            finish(Result.Failed(CODE_INTERNAL, "截屏失败：${e.javaClass.simpleName}"))
        }
    }

    /**
     * HardwareBuffer -> software bitmap. The buffer is closed no matter what.
     *
     * [window] is the area the shot covers, in screen coordinates, or null for a
     * whole-display shot (then the display metrics are the frame, as before).
     */
    private fun toBitmap(result: AccessibilityService.ScreenshotResult, window: Rect?): Result {
        val buffer = result.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
            runCatching { hw?.recycle() }
            if (bmp == null) {
                Result.Failed(CODE_INTERNAL, "截屏失败：拿到的画面读不出来")
            } else {
                val dm = service.resources.displayMetrics
                val w = window?.width() ?: dm.widthPixels
                val h = window?.height() ?: dm.heightPixels
                val sx = if (w > 0) bmp.width / w.toFloat() else 1f
                val sy = if (h > 0) bmp.height / h.toFloat() else 1f
                Result.Ok(bmp, sx, sy, window?.left ?: 0, window?.top ?: 0)
            }
        } catch (e: Throwable) {
            Result.Failed(CODE_INTERNAL, "截屏失败：${e.javaClass.simpleName}")
        } finally {
            runCatching { buffer.close() }
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Our own throttle, not a platform code. */
        const val CODE_THROTTLED = -1
        /** Our own watchdog: the platform callback never arrived. */
        const val CODE_TIMEOUT = -2
        private const val CODE_INTERNAL = 1

        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_STREAK = 6
        private const val HIDE_SETTLE_MS = 120L

        /** How long we wait for the screenshot callback before giving up. */
        private const val TIMEOUT_MS = 3000L

        // Global across instances on purpose: the system limit is per service,
        // and the service may build a new ScreenCapture per call site.
        @Volatile private var lastAttemptAt = 0L
        @Volatile private var failStreak = 0

        /** 1s normally; 1s, 2s, 4s ... capped at 30s while failures repeat. */
        private fun requiredInterval(): Long {
            if (failStreak <= 0) return MIN_INTERVAL_MS
            val shifted = MIN_INTERVAL_MS shl (failStreak - 1).coerceAtMost(MAX_STREAK)
            return shifted.coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Platform error codes, in words a user can act on. */
        fun humanMessage(code: Int): String = when (code) {
            CODE_THROTTLED -> "截屏太频繁"
            CODE_TIMEOUT -> "截屏超时"
            1 -> "截屏失败：内部错误（系统拒绝，可能是该无障碍服务不被允许截屏）"
            2 -> "截屏失败：无障碍服务未声明截屏能力（去设置里把无障碍关掉再开启）"
            3 -> "截屏失败：间隔太短，等一秒再试"
            4 -> "截屏失败：没有有效的显示"
            6 -> "截屏失败：窗口不可见或受保护（FLAG_SECURE），这类界面拿不到画面"
            else -> "截屏失败（码 ${code}）"
        }
    }
}
