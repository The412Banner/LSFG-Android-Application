package com.lsfg.android.session

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicBoolean

class RootCaptureService : RootService() {

    override fun onBind(intent: Intent): IBinder = Impl()

    inner class Impl : IShizukuCaptureService.Stub() {

        private val running = AtomicBoolean(false)
        private var worker: Thread? = null

        override fun startCapture(
            targetUid: Int,
            width: Int,
            height: Int,
            maxFps: Int,
            callback: IShizukuFrameCallback,
        ) {
            stopCapture()
            val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
            running.set(true)
            worker = Thread(
                { runCaptureLoop(targetUid, width, height, periodMs, callback) },
                "lsfg-root-capture",
            ).also { it.start() }
        }

        override fun stopCapture() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }

        override fun describeBackend(): String =
            "root uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"

        override fun destroy() {
            stopCapture()
            System.exit(0)
        }

        private fun runCaptureLoop(
            targetUid: Int,
            width: Int,
            height: Int,
            periodMs: Long,
            callback: IShizukuFrameCallback,
        ) {
            var privilegedError: String? = null
            val captureFrame: () -> HardwareBuffer?

            val privileged = runCatching {
                PrivilegedScreenCapture(width, height, targetUid, allowNoUidFilter = true)
            }.getOrElse { e ->
                privilegedError = e.message ?: e.javaClass.simpleName
                null
            }

            if (privileged != null) {
                captureFrame = { privileged.captureHardwareBuffer() }
            } else {
                val bitmapFn = buildBitmapCaptureFn(width, height)
                if (bitmapFn != null) {
                    Log.i(TAG, "PrivilegedScreenCapture unavailable ($privilegedError); using SurfaceControl.screenshot() fallback")
                    captureFrame = bitmapFn
                } else {
                    callback.onError("Root capture unavailable: $privilegedError; SurfaceControl.screenshot() not found")
                    running.set(false)
                    return
                }
            }

            var lastFrameNs = 0L
            val targetPeriodNs = periodMs * 1_000_000L
            var frameLogCount = 0
            while (running.get()) {
                val started = SystemClock.uptimeMillis()
                val hb = runCatching { captureFrame() }
                    .onFailure {
                        Log.w(TAG, "captureFrame failed", it)
                        callback.onError("Root capture failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                    .getOrNull()

                if (hb != null) {
                    if (frameLogCount < 8) {
                        frameLogCount++
                        Log.i(TAG, "root frame #$frameLogCount uid=$targetUid ${hb.width}x${hb.height} fmt=${hb.format}")
                    }
                    val timestampNs = System.nanoTime()
                    val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                    val pacingJitterNs = if (frameTimeNs > 0L) kotlin.math.abs(frameTimeNs - targetPeriodNs) else 0L
                    lastFrameNs = timestampNs
                    try {
                        callback.onFrameMetrics(timestampNs, frameTimeNs, pacingJitterNs)
                        callback.onFrame(hb, timestampNs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame callback failed", t)
                        running.set(false)
                    } finally {
                        runCatching { hb.close() }
                    }
                }

                val elapsed = SystemClock.uptimeMillis() - started
                val sleepMs = periodMs - elapsed
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        /**
         * Fallback for devices where PrivilegedScreenCapture fails — uses the legacy
         * android.view.SurfaceControl.screenshot() hidden API which takes no display token
         * and works from root UID. The software Bitmap is promoted to a hardware-backed
         * HardwareBuffer so the native pipeline can consume it normally.
         */
        private fun buildBitmapCaptureFn(width: Int, height: Int): (() -> HardwareBuffer?)? {
            val cls = runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull()
                ?: return null
            val all = (cls.methods.asSequence() + cls.declaredMethods.asSequence()).distinct()

            // Prefer 2-arg (int,int) form; accept 5-arg or 7-arg overloads as fallback
            val method = all
                .filter { it.name == "screenshot" }
                .sortedBy { it.parameterTypes.size }
                .firstOrNull { m ->
                    val p = m.parameterTypes
                    (p.size == 2 && p.all { it == Int::class.java }) || p.size == 5 || p.size == 7
                }
                ?.also { it.isAccessible = true }
                ?: return null

            Log.i(TAG, "SurfaceControl.screenshot fallback: ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")

            return fn@{
                try {
                    val bitmap: Bitmap? = when (method.parameterTypes.size) {
                        2 -> method.invoke(null, width, height) as? Bitmap
                        5 -> method.invoke(null, null, width, height, false, 0) as? Bitmap
                        7 -> method.invoke(null, null, width, height, 0, Int.MAX_VALUE, false, 0) as? Bitmap
                        else -> null
                    }
                    if (bitmap == null || bitmap.isRecycled) {
                        bitmap?.recycle()
                        return@fn null
                    }
                    val hw = bitmap.copy(Bitmap.Config.HARDWARE, false)
                    bitmap.recycle()
                    val hb = hw?.hardwareBuffer
                    hw?.recycle()
                    hb
                } catch (e: Exception) {
                    Log.w(TAG, "SurfaceControl.screenshot() threw", e)
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "RootCaptureService"
    }
}
