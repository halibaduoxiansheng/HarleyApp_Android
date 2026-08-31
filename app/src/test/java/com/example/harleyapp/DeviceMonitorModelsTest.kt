package com.example.harleyapp

import com.example.harleyapp.system.isCpuThermalZoneType
import com.example.harleyapp.system.normalizeCpuTemperature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证CPU温度节点识别与单位换算，避免把电池热区或异常原始值显示为CPU温度。
 *
 * 使用方法：
 * 在项目根目录运行gradlew testDebugUnitTest，由JUnit自动执行本测试类。
 */
class DeviceMonitorModelsTest {

    /**
     * 验证摄氏度和毫摄氏度都能转换，并过滤不合理数值。
     *
     * @return 无返回值；单位换算或合法范围判断错误时由JUnit报告失败。
     */
    @Test
    fun cpuTemperatureNormalizationHandlesVendorUnits() {
        assertEquals(46.5f, normalizeCpuTemperature(46_500f) ?: 0f, 0.001f)
        assertEquals(43.2f, normalizeCpuTemperature(43.2f) ?: 0f, 0.001f)
        assertNull(normalizeCpuTemperature(Float.NaN))
        assertNull(normalizeCpuTemperature(180_000f))
    }

    /**
     * 验证CPU、SOC和集群热区可被识别，同时明确排除电池、GPU和机身温度。
     *
     * @return 无返回值；热区分类可能误导用户时由JUnit报告失败。
     */
    @Test
    fun cpuThermalTypeExcludesOtherComponents() {
        assertTrue(isCpuThermalZoneType("cpu-0-0-us"))
        assertTrue(isCpuThermalZoneType("soc_thermal"))
        assertTrue(isCpuThermalZoneType("big_core_cluster"))
        assertFalse(isCpuThermalZoneType("battery"))
        assertFalse(isCpuThermalZoneType("gpu-thermal"))
        assertFalse(isCpuThermalZoneType("skin-msm-therm"))
    }
}
