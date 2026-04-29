package com.lsfg.android.session

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.lang.reflect.Method

/**
 * Calls the hidden [android.window.ScreenCapture] / [android.view.SurfaceControl] API
 * via reflection to capture frames from the display.
 *
 * With [allowNoUidFilter]=false (Shizuku): requires a UID-filtered backend so only the
 * target app's frames are captured. Requires at minimum shell UID.
 *
 * With [allowNoUidFilter]=true (root): falls back to full-display capture if UID-filtered
 * APIs are unavailable. Also attempts setCaptureSecureLayers to bypass FLAG_SECURE.
 * Requires root UID for the secure-layer path to be honoured by SurfaceFlinger.
 */
internal class PrivilegedScreenCapture(
    width: Int,
    height: Int,
    targetUid: Int,
    private val allowNoUidFilter: Boolean = false,
) {
    private val captureDisplay: Method
    private val args: Any
    private val getHardwareBuffer: Method

    init {
        val failures = mutableListOf<String>()
        val classNames = listOf("android.window.ScreenCapture", "android.view.SurfaceControl")

        val backend = classNames.firstNotNullOfOrNull { className ->
            runCatching { buildBackend(className, width, height, targetUid, requireUidFilter = true) }
                .onFailure { failures.add("$className+uid: ${it.message}") }
                .getOrNull()
        } ?: run {
            if (!allowNoUidFilter) return@run null
            Log.w(TAG, "UID-filtered capture failed (${failures.joinToString("; ")}); trying full-display fallback")
            classNames.firstNotNullOfOrNull { className ->
                runCatching { buildBackend(className, width, height, targetUid, requireUidFilter = false) }
                    .onFailure { failures.add("$className+nouid: ${it.message}") }
                    .getOrNull()
            }
        } ?: throw IllegalStateException(
            "No ScreenCapture backend available — ${failures.joinToString("; ")}"
        )

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
        requireUidFilter: Boolean,
    ): Backend {
        val captureClass = Class.forName(captureClassName)
        val builderClass = Class.forName("$captureClassName\$DisplayCaptureArgs\$Builder")
        val argsClass = Class.forName("$captureClassName\$DisplayCaptureArgs")
        val screenshotClass = Class.forName("$captureClassName\$ScreenshotHardwareBuffer")
        val builder = createDisplayCaptureArgsBuilder(captureClassName, builderClass)
        invokeOptional(builderClass, builder, "setSize", intArrayOf(width, height))
        invokeOptional(builderClass, builder, "setPixelFormat", intArrayOf(PixelFormat.RGBA_8888))
        if (!invokeSetUid(builderClass, builder, targetUid.toLong())) {
            if (requireUidFilter) throw IllegalStateException("setUid not found in $captureClassName")
            Log.i(TAG, "No setUid in $captureClassName — full-display capture mode")
            // From root UID, try to capture secure (FLAG_SECURE) layers too
            invokeOptionalBool(builderClass, builder, "setCaptureSecureLayers", true)
        }
        val builtArgs = builderClass.getMethod("build").invoke(builder)
            ?: throw IllegalStateException("$captureClassName args build() returned null")
        return Backend(
            captureDisplay = findSingleArgMethod(captureClass, "captureDisplay", argsClass),
            args = builtArgs,
            getHardwareBuffer = findNoArgMethod(screenshotClass, "getHardwareBuffer"),
        )
    }

    private fun createDisplayCaptureArgsBuilder(captureClassName: String, builderClass: Class<*>): Any {
        val constructors = (builderClass.declaredConstructors.asSequence() +
            builderClass.constructors.asSequence()).distinct().toList()
        constructors.forEach { it.isAccessible = true }

        var tokenError: String? = null

        // IBinder constructor with real display token
        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && IBinder::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }.forEach { ctor ->
            runCatching {
                val displayToken = findDisplayToken()
                Log.i(TAG, "$captureClassName builder: using IBinder display token")
                return ctor.newInstance(displayToken)
            }.onFailure {
                tokenError = it.message
                Log.w(TAG, "$captureClassName IBinder builder unavailable", it)
            }
        }

        // int display-ID constructor
        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder: using display id ${Display.DEFAULT_DISPLAY}")
                return ctor.newInstance(Display.DEFAULT_DISPLAY)
            }.onFailure { Log.w(TAG, "$captureClassName int-id builder unavailable", it) }
        }

        // no-arg constructor
        constructors.filter { ctor ->
            ctor.parameterTypes.isEmpty()
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder: using no-arg constructor")
                return ctor.newInstance()
            }.onFailure { Log.w(TAG, "$captureClassName no-arg builder unavailable", it) }
        }

        // Last resort: IBinder constructor with null token (some ROMs treat null as default display)
        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && IBinder::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder: trying null token fallback")
                @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                return ctor.newInstance(null as IBinder?)
            }.onFailure { Log.w(TAG, "$captureClassName null-token builder threw", it) }
        }

        val ctorSigs = constructors.joinToString { ctor ->
            ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        val tokenDetail = tokenError?.let { "; token: $it" } ?: ""
        throw IllegalStateException(
            "No usable $captureClassName DisplayCaptureArgs.Builder constructor: $ctorSigs$tokenDetail"
        )
    }

    private fun findDisplayToken(): IBinder {
        findDisplayTokenFromDisplayManagerGlobal()?.let { return it }
        findDisplayTokenFromDisplayService()?.let { return it }

        for (className in listOf("android.view.DisplayControl", "android.view.SurfaceControl")) {
            val cls = runCatching { Class.forName(className) }
                .onFailure { Log.w(TAG, "Display token class unavailable: $className") }
                .getOrNull() ?: continue
            findDisplayTokenFromClass(className, cls)?.let { return it }
        }
        throw IllegalStateException("No display token API is available")
    }

    private fun findDisplayTokenFromDisplayManagerGlobal(): IBinder? {
        return runCatching {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = allMethods(globalClass).firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
                ?.also { it.isAccessible = true }?.invoke(null)
                ?: return@runCatching null

            allMethods(globalClass).firstOrNull {
                it.name == "getDisplayToken" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.also { it.isAccessible = true }?.invoke(global, Display.DEFAULT_DISPLAY)
                ?.let {
                    Log.i(TAG, "Display token from DisplayManagerGlobal.getDisplayToken(int)")
                    return@runCatching it as? IBinder
                }

            // Try via the mDm service field
            allFields(globalClass).firstOrNull { it.name == "mDm" }
                ?.also { it.isAccessible = true }?.get(global)
                ?.let { dm ->
                    allMethods(dm.javaClass).firstOrNull { m ->
                        m.name == "getDisplayToken" &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType
                    }?.also { it.isAccessible = true }?.invoke(dm, Display.DEFAULT_DISPLAY)
                        ?.let {
                            Log.i(TAG, "Display token from DisplayManagerGlobal.mDm.getDisplayToken")
                            return@runCatching it as? IBinder
                        }
                }

            // Try via DisplayInfo token field
            val info = allMethods(globalClass).firstOrNull {
                it.name == "getDisplayInfo" &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.also { it.isAccessible = true }?.invoke(global, Display.DEFAULT_DISPLAY)
                ?: return@runCatching null
            allFields(info.javaClass).firstOrNull { field ->
                IBinder::class.java.isAssignableFrom(field.type) &&
                    field.name.contains("token", ignoreCase = true)
            }?.also { it.isAccessible = true }?.get(info)
                ?.also { Log.i(TAG, "Display token from DisplayManagerGlobal.DisplayInfo.token") }
                as? IBinder
        }.onFailure { Log.w(TAG, "DisplayManagerGlobal token unavailable: ${it.message}") }
            .getOrNull()
    }

    private fun findDisplayTokenFromDisplayService(): IBinder? {
        return runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = allMethods(serviceManagerClass).firstOrNull {
                it.name == "getService" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }?.also { it.isAccessible = true }
            val displayBinder = getService?.invoke(null, "display") as? IBinder
                ?: return@runCatching null
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val displayManager = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, displayBinder) ?: return@runCatching null
            // Try getDisplayToken(int) and getDisplayToken(long)
            allMethods(displayManager.javaClass).firstOrNull { m ->
                m.name == "getDisplayToken" && m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        m.parameterTypes[0] == Long::class.javaPrimitiveType)
            }?.also { it.isAccessible = true }?.let { m ->
                val token = when (m.parameterTypes[0]) {
                    Int::class.javaPrimitiveType -> m.invoke(displayManager, Display.DEFAULT_DISPLAY)
                    else -> m.invoke(displayManager, Display.DEFAULT_DISPLAY.toLong())
                } as? IBinder
                if (token != null) Log.i(TAG, "Display token from IDisplayManager.getDisplayToken")
                token
            }
        }.onFailure { Log.w(TAG, "IDisplayManager token unavailable: ${it.message}") }
            .getOrNull()
    }

    private fun findDisplayTokenFromClass(className: String, cls: Class<*>): IBinder? {
        // getPhysicalDisplayIds() + getPhysicalDisplayToken(long)
        runCatching {
            val ids = allMethods(cls).firstOrNull {
                it.name == "getPhysicalDisplayIds" && it.parameterTypes.isEmpty()
            }?.also { it.isAccessible = true }?.invoke(null) as? LongArray
                ?: throw NoSuchMethodException("$className.getPhysicalDisplayIds()")
            val tokenMethod = allMethods(cls).firstOrNull { m ->
                m.name == "getPhysicalDisplayToken" &&
                    m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }?.also { it.isAccessible = true }
                ?: throw NoSuchMethodException("$className.getPhysicalDisplayToken(long)")
            for (id in ids) {
                (tokenMethod.invoke(null, id) as? IBinder)?.let { token ->
                    Log.i(TAG, "Display token from $className.getPhysicalDisplayToken($id)")
                    return token
                }
            }
        }.onFailure { Log.w(TAG, "$className physicalDisplay token unavailable: ${it.message}") }

        // getInternalDisplayToken()
        runCatching {
            allMethods(cls).firstOrNull {
                it.name == "getInternalDisplayToken" && it.parameterTypes.isEmpty()
            }?.also { it.isAccessible = true }?.invoke(null) as? IBinder
                ?: throw NoSuchMethodException("$className.getInternalDisplayToken()")
        }.onSuccess { token ->
            Log.i(TAG, "Display token from $className.getInternalDisplayToken")
            return token
        }.onFailure { Log.w(TAG, "$className internalDisplayToken unavailable: ${it.message}") }

        // getBuiltInDisplay(int)
        runCatching {
            allMethods(cls).firstOrNull { m ->
                m.name == "getBuiltInDisplay" &&
                    m.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.also { it.isAccessible = true }?.invoke(null, 0) as? IBinder
                ?: throw NoSuchMethodException("$className.getBuiltInDisplay(int)")
        }.onSuccess { token ->
            Log.i(TAG, "Display token from $className.getBuiltInDisplay(0)")
            return token
        }.onFailure { Log.w(TAG, "$className builtInDisplay token unavailable: ${it.message}") }

        return null
    }

    private fun invokeSetUid(builderClass: Class<*>, builder: Any, uid: Long): Boolean {
        val methods = allMethods(builderClass).filter { it.name == "setUid" && it.parameterTypes.size == 1 }
        for (method in methods) {
            method.isAccessible = true
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

    private fun invokeOptional(builderClass: Class<*>, builder: Any, name: String, args: IntArray) {
        val types = Array(args.size) { Int::class.javaPrimitiveType }
        runCatching {
            allMethods(builderClass).firstOrNull {
                it.name == name && it.parameterTypes.contentEquals(types)
            }?.also { it.isAccessible = true }?.invoke(builder, *args.toTypedArray())
        }
    }

    private fun invokeOptionalBool(builderClass: Class<*>, builder: Any, name: String, value: Boolean) {
        runCatching {
            allMethods(builderClass).firstOrNull {
                it.name == name && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.also { it.isAccessible = true }?.invoke(builder, value)
                ?.also { Log.i(TAG, "Called $name($value)") }
        }.onFailure { Log.d(TAG, "$name not available: ${it.message}") }
    }

    private fun findSingleArgMethod(cls: Class<*>, name: String, argClass: Class<*>): Method {
        return allMethods(cls).firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(argClass)
        }?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException("${cls.name}.$name(${argClass.name})")
    }

    private fun findNoArgMethod(cls: Class<*>, name: String): Method {
        return allMethods(cls).firstOrNull { method ->
            method.name == name && method.parameterTypes.isEmpty()
        }?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException("${cls.name}.$name()")
    }

    private fun allMethods(cls: Class<*>) =
        (cls.methods.asSequence() + cls.declaredMethods.asSequence()).distinct()

    private fun allFields(cls: Class<*>) =
        (cls.fields.asSequence() + cls.declaredFields.asSequence()).distinct()

    private data class Backend(
        val captureDisplay: Method,
        val args: Any,
        val getHardwareBuffer: Method,
    )

    companion object {
        private const val TAG = "PrivilegedCapture"
    }
}
