package com.example.harleyapp

import com.example.harleyapp.ui.screens.calculateDualCameraZoomRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证双摄页面的倍率累计与镜头能力边界，不依赖Android设备或真实相机。
 *
 * 使用方法：
 * 在项目根目录执行 `gradlew testDebugUnitTest`，JUnit会自动运行本类全部测试。实机手势注入与
 * CameraX硬件执行属于另一验证层，本类只负责确保输入换算不会越界或接受无效浮点数。
 */
class DualCameraZoomModelsTest {

    /**
     * 验证放大因子会以上一帧目标倍率为基准继续累计。
     *
     * @return 无返回值；累计结果不正确时由JUnit报告失败。
     */
    @Test
    fun zoomInAccumulatesFromCurrentRequestedRatio() {
        val result = calculateDualCameraZoomRatio(
            currentRatio = 1.5f,
            scaleFactor = 1.2f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 10f
        )

        assertEquals(1.8f, result ?: Float.NaN, 0.0001f)
    }

    /**
     * 验证放大超过镜头最大倍率时会停在最大值，不把非法倍率提交给CameraX。
     *
     * @return 无返回值；最大倍率夹取失败时由JUnit报告失败。
     */
    @Test
    fun zoomInIsClampedToCameraMaximum() {
        val result = calculateDualCameraZoomRatio(
            currentRatio = 8f,
            scaleFactor = 2f,
            minZoomRatio = 1f,
            maxZoomRatio = 10f
        )

        assertEquals(10f, result ?: Float.NaN, 0f)
    }

    /**
     * 验证支持超广角倍率的镜头缩小时会停在其最小值。
     *
     * @return 无返回值；最小倍率夹取失败时由JUnit报告失败。
     */
    @Test
    fun zoomOutIsClampedToCameraMinimum() {
        val result = calculateDualCameraZoomRatio(
            currentRatio = 1f,
            scaleFactor = 0.1f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 8f
        )

        assertEquals(0.5f, result ?: Float.NaN, 0f)
    }

    /**
     * 验证NaN、非正缩放因子与倒置倍率区间都会被拒绝。
     *
     * @return 无返回值；任一无效输入得到目标倍率时由JUnit报告失败。
     */
    @Test
    fun invalidZoomInputsAreRejected() {
        assertNull(calculateDualCameraZoomRatio(Float.NaN, 1.2f, 1f, 8f))
        assertNull(calculateDualCameraZoomRatio(1f, 0f, 1f, 8f))
        assertNull(calculateDualCameraZoomRatio(1f, Float.POSITIVE_INFINITY, 1f, 8f))
        assertNull(calculateDualCameraZoomRatio(1f, 1.2f, 8f, 1f))
    }
}
