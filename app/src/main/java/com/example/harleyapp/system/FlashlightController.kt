package com.example.harleyapp.system

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * 当前手机手电筒及亮度档位能力。
 *
 * @param isAvailable true表示至少找到一个带闪光灯的摄像头。
 * @param maximumStrengthLevel Android和硬件共同报告的最高亮度档位；固定亮度设备为1。
 * @param defaultStrengthLevel 硬件建议的默认亮度档位；固定亮度设备为1。
 */
data class FlashlightCapability(
    val isAvailable: Boolean,
    val maximumStrengthLevel: Int,
    val defaultStrengthLevel: Int
) {
    /** true表示当前设备可以通过系统接口选择多档手电筒亮度。 */
    val supportsStrengthControl: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            maximumStrengthLevel > 1
}

/**
 * 一次手电筒开关命令的同步结果。
 *
 * @param success true表示命令已经被Android相机服务接受。
 * @param message 失败时供界面展示的中文原因；成功时为null。
 */
data class FlashlightControlResult(
    val success: Boolean,
    val message: String? = null
)

/**
 * 不打开相机预览，直接通过Camera2系统服务控制后置闪光灯。
 *
 * 使用方法：
 * 使用Application Context创建并复用同一个实例，先读取[capability]决定是否显示亮度滑块；点亮时
 * 调用[turnOn]，结束、异常、页面销毁和进入后台时都必须调用[turnOff]。本类捕获权限撤销、相机
 * 被其他应用占用和硬件断开异常，不会把相机服务异常直接抛到Compose页面。
 *
 * @param context Android上下文，内部只保留Application Context提供的相机系统服务。
 */
class FlashlightController(context: Context) {

    private val cameraManager = context.applicationContext.getSystemService(CameraManager::class.java)
    private val cameraId = findTorchCameraId()

    /** 当前设备在页面生命周期内稳定使用的闪光灯能力快照。 */
    val capability: FlashlightCapability = readCapability(cameraId)

    /**
     * 按指定亮度点亮手电筒。
     *
     * Android 13及以上且硬件支持多档亮度时使用系统强度接口；旧系统或固定亮度设备自动退化为
     * 普通常亮。调用方即使已经检查权限，也仍需根据返回值处理运行期间权限被撤销的情况。
     *
     * @param strengthLevel 从1开始的目标亮度档位，越界值会被限制到设备报告范围。
     *
     * @return 命令成功返回success=true；失败时返回可直接展示的中文原因。
     */
    @SuppressLint("MissingPermission")
    fun turnOn(strengthLevel: Int): FlashlightControlResult {
        val targetCameraId = cameraId
            ?: return FlashlightControlResult(false, "当前设备没有可用的后置闪光灯")

        return runTorchCommand {
            if (capability.supportsStrengthControl) {
                cameraManager.turnOnTorchWithStrengthLevel(
                    targetCameraId,
                    strengthLevel.coerceIn(1, capability.maximumStrengthLevel)
                )
            } else {
                cameraManager.setTorchMode(targetCameraId, true)
            }
        }
    }

    /**
     * 立即关闭当前控制的手电筒。
     *
     * 使用方法：
     * 正常到时、用户停止、页面退出、应用进入后台以及异常清理路径都调用本函数。重复关闭是安全
     * 的；若相机服务已经不可用，函数返回失败信息但不会抛出异常。
     *
     * @return 命令成功返回success=true；失败时返回可直接展示的中文原因。
     */
    @SuppressLint("MissingPermission")
    fun turnOff(): FlashlightControlResult {
        val targetCameraId = cameraId
            ?: return FlashlightControlResult(false, "当前设备没有可用的后置闪光灯")

        return runTorchCommand {
            cameraManager.setTorchMode(targetCameraId, false)
        }
    }

    /**
     * 优先查找带闪光灯的后置摄像头，找不到时再使用设备上的其他闪光灯。
     *
     * @return 可用于Torch API的摄像头id；查询失败或设备没有闪光灯时返回null。
     */
    private fun findTorchCameraId(): String? {
        return try {
            val flashCameraIds = cameraManager.cameraIdList.filter { candidateId ->
                cameraManager.getCameraCharacteristics(candidateId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            flashCameraIds.firstOrNull { candidateId ->
                cameraManager.getCameraCharacteristics(candidateId)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: flashCameraIds.firstOrNull()
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Failed to query torch camera", error)
            null
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unexpected failure while querying torch camera", error)
            null
        }
    }

    /**
     * 读取指定闪光灯的亮度档位能力，并为旧系统提供固定亮度退化值。
     *
     * @param targetCameraId 已确定带闪光灯的摄像头id，null表示没有可用闪光灯。
     *
     * @return 可供界面稳定使用的能力对象；任何查询失败都会返回不可用能力。
     */
    private fun readCapability(targetCameraId: String?): FlashlightCapability {
        if (targetCameraId == null) {
            return FlashlightCapability(
                isAvailable = false,
                maximumStrengthLevel = FIXED_STRENGTH_LEVEL,
                defaultStrengthLevel = FIXED_STRENGTH_LEVEL
            )
        }

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(targetCameraId)
            val maximumStrengthLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                    ?.coerceAtLeast(FIXED_STRENGTH_LEVEL)
                    ?: FIXED_STRENGTH_LEVEL
            } else {
                FIXED_STRENGTH_LEVEL
            }
            val defaultStrengthLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL)
                    ?.coerceIn(FIXED_STRENGTH_LEVEL, maximumStrengthLevel)
                    ?: FIXED_STRENGTH_LEVEL
            } else {
                FIXED_STRENGTH_LEVEL
            }
            FlashlightCapability(
                isAvailable = true,
                maximumStrengthLevel = maximumStrengthLevel,
                defaultStrengthLevel = defaultStrengthLevel
            )
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Failed to read torch capability", error)
            FlashlightCapability(false, FIXED_STRENGTH_LEVEL, FIXED_STRENGTH_LEVEL)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unexpected failure while reading torch capability", error)
            FlashlightCapability(false, FIXED_STRENGTH_LEVEL, FIXED_STRENGTH_LEVEL)
        }
    }

    /**
     * 统一执行Torch命令并把系统异常收敛成页面可处理的结果。
     *
     * @param command 实际调用CameraManager的无返回值命令。
     *
     * @return 命令正常完成时返回成功；权限、占用或硬件异常时返回对应失败说明。
     */
    private inline fun runTorchCommand(command: () -> Unit): FlashlightControlResult {
        return try {
            command()
            FlashlightControlResult(success = true)
        } catch (error: SecurityException) {
            Log.e(TAG, "Camera permission missing while controlling torch", error)
            FlashlightControlResult(false, "相机权限不可用，请重新授权后再试")
        } catch (error: CameraAccessException) {
            Log.e(TAG, "Camera service rejected torch command", error)
            FlashlightControlResult(false, cameraAccessMessage(error.reason))
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Invalid camera id while controlling torch", error)
            FlashlightControlResult(false, "当前闪光灯暂时不可用")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unexpected failure while controlling torch", error)
            FlashlightControlResult(false, "手电筒控制失败，请稍后重试")
        }
    }

    private companion object {
        const val TAG = "FlashlightController"
        const val FIXED_STRENGTH_LEVEL = 1

        /**
         * 把Camera2失败原因转换为简短中文提示，详细异常仍只写入英文日志。
         *
         * @param reason CameraAccessException提供的稳定原因码。
         *
         * @return 适合直接展示在功能页的故障说明。
         */
        fun cameraAccessMessage(reason: Int): String {
            return when (reason) {
                CameraAccessException.CAMERA_IN_USE,
                CameraAccessException.MAX_CAMERAS_IN_USE ->
                    "摄像头正在被其他功能占用，请关闭相机或扫码页面后重试"

                CameraAccessException.CAMERA_DISABLED ->
                    "系统安全策略已禁用摄像头"

                CameraAccessException.CAMERA_DISCONNECTED ->
                    "闪光灯已断开，请稍后重试"

                else -> "相机服务暂时不可用，请稍后重试"
            }
        }
    }
}
