package com.lsfg.android.session

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuCaptureUserService : IShizukuCaptureService.Stub() {

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
        worker = Thread({
            runCaptureLoop(targetUid, width, height, periodMs, callback)
        }, "lsfg-shizuku-capture").also { it.start() }
    }

    override fun stopCapture() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    override fun describeBackend(): String {
        return "uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"
    }

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
        val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
            .getOrElse { e ->
                Log.w(TAG, "Unable to initialize privileged capture", e)
                callback.onError("Shizuku capture unavailable: ${e.message ?: e.javaClass.simpleName}")
                running.set(false)
                return
            }

        var lastFrameNs = 0L
        val targetPeriodNs = periodMs * 1_000_000L
        var frameLogCount = 0
        while (running.get()) {
            val started = SystemClock.uptimeMillis()
            val hb = runCatching { capture.captureHardwareBuffer() }
                .onFailure {
                    Log.w(TAG, "captureHardwareBuffer failed", it)
                    callback.onError("Shizuku capture failed: ${it.message ?: it.javaClass.simpleName}")
                }
                .getOrNull()

            if (hb != null) {
                if (frameLogCount < 8) {
                    frameLogCount++
                    Log.i(TAG, "captured frame #$frameLogCount uid=$targetUid ${hb.width}x${hb.height} fmt=${hb.format}")
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

    private class PrivilegedScreenCapture(
        width: Int,
        height: Int,
        targetUid: Int,
    ) {
        private val captureDisplay: Method
        private val args: Any
        private val getHardwareBuffer: Method

        init {
            val backend = listOf(
                "android.window.ScreenCapture",
                "android.view.SurfaceControl",
            ).firstNotNullOfOrNull { className ->
                runCatching { buildBackend(className, width, height, targetUid) }
                    .onFailure { Log.w(TAG, "Screen capture backend unavailable: $className", it) }
                    .getOrNull()
            }
                ?: throw IllegalStateException("No privileged ScreenCapture backend with UID filter is available")
            captureDisplay = backend.captureDisplay
            args = backend.args
            getHardwareBuffer = backend.getHardwareBuffer
        }

        fun captureHardwareBuffer(): HardwareBuffer? {
            val screenshot = captureDisplay.invoke(null, args) ?: return null
            return getHardwareBuffer.invoke(screenshot) as? HardwareBuffer
        }

        private fun buildBackend(
            captureClassName: String,
            width: Int,
            height: Int,
            targetUid: Int,
        ): Backend {
            val captureClass = Class.forName(captureClassName)
            val builderClass = Class.forName("$captureClassName\$DisplayCaptureArgs\$Builder")
            val argsClass = Class.forName("$captureClassName\$DisplayCaptureArgs")
            val screenshotClass = Class.forName("$captureClassName\$ScreenshotHardwareBuffer")
            val builder = createDisplayCaptureArgsBuilder(captureClassName, builderClass)
            invokeOptional(builderClass, builder, "setSize", intArrayOf(width, height))
            invokeOptional(builderClass, builder, "setPixelFormat", intArrayOf(PixelFormat.RGBA_8888))
            if (!invokeSetUid(builderClass, builder, targetUid.toLong())) {
                throw IllegalStateException("UID filter missing in $captureClassName")
            }
            val builtArgs = builderClass.getMethod("build").invoke(builder)
                ?: throw IllegalStateException("$captureClassName args build returned null")
            return Backend(
                captureDisplay = findSingleArgMethod(captureClass, "captureDisplay", argsClass),
                args = builtArgs,
                getHardwareBuffer = findNoArgMethod(screenshotClass, "getHardwareBuffer"),
            )
        }

        private fun createDisplayCaptureArgsBuilder(captureClassName: String, builderClass: Class<*>): Any {
            val constructors = builderClass.declaredConstructors
            constructors.forEach { it.isAccessible = true }

            constructors.filter { ctor ->
                ctor.parameterTypes.size == 1 && IBinder::class.java.isAssignableFrom(ctor.parameterTypes[0])
            }.forEach { ctor ->
                runCatching {
                    val displayToken = findDisplayToken()
                    Log.i(TAG, "$captureClassName builder using IBinder display token")
                    return ctor.newInstance(displayToken)
                }.onFailure { Log.w(TAG, "$captureClassName IBinder builder unavailable", it) }
            }

            constructors.filter { ctor ->
                ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType
            }.forEach { ctor ->
                runCatching {
                    Log.i(TAG, "$captureClassName builder using logical display id ${Display.DEFAULT_DISPLAY}")
                    return ctor.newInstance(Display.DEFAULT_DISPLAY)
                }.onFailure { Log.w(TAG, "$captureClassName display-id builder unavailable", it) }
            }

            constructors.filter { ctor ->
                ctor.parameterTypes.isEmpty()
            }.forEach { ctor ->
                runCatching {
                    Log.i(TAG, "$captureClassName builder using no-arg constructor")
                    return ctor.newInstance()
                }.onFailure { Log.w(TAG, "$captureClassName no-arg builder unavailable", it) }
            }

            throw IllegalStateException(
                "No usable $captureClassName DisplayCaptureArgs.Builder constructor: " +
                    constructors.joinToString { ctor ->
                        ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                    }
            )
        }

        private fun findDisplayToken(): IBinder {
            findDisplayTokenFromDisplayManagerGlobal()?.let { return it }
            findDisplayTokenFromDisplayService()?.let { return it }

            for (className in listOf("android.view.DisplayControl", "android.view.SurfaceControl")) {
                val cls = runCatching { Class.forName(className) }
                    .onFailure { Log.w(TAG, "Display token class unavailable: $className", it) }
                    .getOrNull() ?: continue

                findDisplayTokenFromDisplayControlClass(className, cls)?.let { return it }
            }
            throw IllegalStateException("No display token API is available")
        }

        private fun findDisplayTokenFromDisplayManagerGlobal(): IBinder? {
            return runCatching {
                val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val global = globalClass.getMethod("getInstance").invoke(null)
                runCatching {
                    globalClass.getMethod("getDisplayToken", Int::class.javaPrimitiveType)
                        .invoke(global, Display.DEFAULT_DISPLAY) as? IBinder
                }.getOrNull()?.let { token ->
                    Log.i(TAG, "Display token resolved from DisplayManagerGlobal.getDisplayToken")
                    return@runCatching token
                }
                runCatching {
                    val dmField = globalClass.declaredFields.firstOrNull { it.name == "mDm" }
                        ?: return@runCatching null
                    dmField.isAccessible = true
                    val dm = dmField.get(global) ?: return@runCatching null
                    dm.javaClass.methods.firstOrNull { method ->
                        method.name == "getDisplayToken" &&
                            method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                    }?.invoke(dm, Display.DEFAULT_DISPLAY) as? IBinder
                }.getOrNull()?.let { token ->
                    Log.i(TAG, "Display token resolved from DisplayManagerGlobal.mDm.getDisplayToken")
                    return@runCatching token
                }
                val info = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                    .invoke(global, Display.DEFAULT_DISPLAY)
                    ?: return@runCatching null
                val tokenField = info.javaClass.declaredFields.firstOrNull { field ->
                    IBinder::class.java.isAssignableFrom(field.type) &&
                        field.name.contains("token", ignoreCase = true)
                } ?: return@runCatching null
                tokenField.isAccessible = true
                (tokenField.get(info) as? IBinder)
                    ?.also { Log.i(TAG, "Display token resolved from DisplayManagerGlobal.${tokenField.name}") }
            }.onFailure { Log.w(TAG, "DisplayManagerGlobal display token unavailable", it) }
                .getOrNull()
        }

        private fun findDisplayTokenFromDisplayService(): IBinder? {
            return runCatching {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val displayBinder = serviceManager.getMethod("getService", String::class.java)
                    .invoke(null, "display") as? IBinder
                    ?: return@runCatching null
                val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                val displayManager = stub.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, displayBinder)
                    ?: return@runCatching null
                displayManager.javaClass.methods.firstOrNull { method ->
                    method.name == "getDisplayToken" &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }?.invoke(displayManager, Display.DEFAULT_DISPLAY) as? IBinder
            }.onSuccess {
                if (it != null) Log.i(TAG, "Display token resolved from IDisplayManager.getDisplayToken")
            }.onFailure { Log.w(TAG, "IDisplayManager display token unavailable", it) }
                .getOrNull()
        }

        private fun findDisplayTokenFromDisplayControlClass(className: String, cls: Class<*>): IBinder? {
            runCatching {
                val ids = cls.methods.firstOrNull { it.name == "getPhysicalDisplayIds" && it.parameterTypes.isEmpty() }
                    ?.invoke(null) as? LongArray
                    ?: throw NoSuchMethodException("$className.getPhysicalDisplayIds()")
                val tokenMethod = cls.methods.firstOrNull { method ->
                    method.name == "getPhysicalDisplayToken" &&
                        method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
                } ?: throw NoSuchMethodException("$className.getPhysicalDisplayToken(long)")
                for (id in ids) {
                    (tokenMethod.invoke(null, id) as? IBinder)?.let { token ->
                        Log.i(TAG, "Display token resolved from $className.getPhysicalDisplayToken($id)")
                        return token
                    }
                }
            }.onFailure { Log.w(TAG, "$className physical display token unavailable", it) }

            runCatching {
                cls.methods.firstOrNull { it.name == "getInternalDisplayToken" && it.parameterTypes.isEmpty() }
                    ?.invoke(null) as? IBinder
                    ?: throw NoSuchMethodException("$className.getInternalDisplayToken()")
            }.onSuccess {
                Log.i(TAG, "Display token resolved from $className.getInternalDisplayToken")
                return it
            }.onFailure { Log.w(TAG, "$className internal display token unavailable", it) }

            runCatching {
                cls.methods.firstOrNull { method ->
                    method.name == "getBuiltInDisplay" &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }?.invoke(null, 0) as? IBinder
                    ?: throw NoSuchMethodException("$className.getBuiltInDisplay(int)")
            }.onSuccess {
                Log.i(TAG, "Display token resolved from $className.getBuiltInDisplay")
                return it
            }.onFailure { Log.w(TAG, "$className built-in display token unavailable", it) }

            return null
        }

        private fun invokeSetUid(builderClass: Class<*>, builder: Any, uid: Long): Boolean {
            val methods = builderClass.methods.filter { it.name == "setUid" && it.parameterTypes.size == 1 }
            for (method in methods) {
                runCatching {
                    when (method.parameterTypes[0]) {
                        Long::class.javaPrimitiveType -> method.invoke(builder, uid)
                        Int::class.javaPrimitiveType -> method.invoke(builder, uid.toInt())
                        else -> return@runCatching
                    }
                    return true
                }
            }
            return false
        }

        private fun invokeOptional(
            builderClass: Class<*>,
            builder: Any,
            name: String,
            args: IntArray,
        ) {
            val types = Array(args.size) { Int::class.javaPrimitiveType }
            runCatching {
                builderClass.getMethod(name, *types).invoke(builder, *args.toTypedArray())
            }
        }

        private fun findSingleArgMethod(cls: Class<*>, name: String, argClass: Class<*>): Method {
            return (cls.methods.asSequence() + cls.declaredMethods.asSequence())
                .firstOrNull { method ->
                    method.name == name &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].isAssignableFrom(argClass)
                }
                ?.also { it.isAccessible = true }
                ?: throw NoSuchMethodException("${cls.name}.$name(${argClass.name})")
        }

        private fun findNoArgMethod(cls: Class<*>, name: String): Method {
            return (cls.methods.asSequence() + cls.declaredMethods.asSequence())
                .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
                ?.also { it.isAccessible = true }
                ?: throw NoSuchMethodException("${cls.name}.$name()")
        }

        private data class Backend(
            val captureDisplay: Method,
            val args: Any,
            val getHardwareBuffer: Method,
        )
    }

    companion object {
        private const val TAG = "ShizukuUserCapture"
    }
}
