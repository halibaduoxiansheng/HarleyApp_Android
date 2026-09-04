package com.example.harleyapp.charging

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证充电界面使用的单位转换、异常值过滤和供电来源映射。 */
class ChargingBatteryReaderTest {

    /**
     * 验证非100量程仍能换算为正确整数百分比，并拒绝越界电量。
     */
    @Test
    fun normalizeBatteryPercentage_scalesAndRejectsInvalidValues() {
        assertEquals(50, normalizeBatteryPercentage(level = 128, scale = 255))
        assertEquals(100, normalizeBatteryPercentage(level = 255, scale = 255))
        assertNull(normalizeBatteryPercentage(level = 256, scale = 255))
        assertNull(normalizeBatteryPercentage(level = 1, scale = 0))
    }

    /**
     * 验证Android规定的微安单位转换为毫安，并保留充电和放电方向。
     */
    @Test
    fun normalizeBatteryCurrentMilliAmps_preservesDirectionAndUnsupportedValue() {
        assertEquals(1_850f, normalizeBatteryCurrentMilliAmps(1_850_000)!!, 0.001f)
        assertEquals(-620f, normalizeBatteryCurrentMilliAmps(-620_000)!!, 0.001f)
        assertNull(normalizeBatteryCurrentMilliAmps(Int.MIN_VALUE))
    }

    /**
     * 验证电压与十分之一摄氏度使用公开Android单位换算，并拒绝明显异常值。
     */
    @Test
    fun normalizeVoltageAndTemperature_convertPublicAndroidUnits() {
        assertEquals(4.235f, normalizeBatteryVoltageVolts(4_235)!!, 0.0001f)
        assertEquals(36.8f, normalizeBatteryTemperatureCelsius(368)!!, 0.0001f)
        assertNull(normalizeBatteryVoltageVolts(500))
        assertNull(normalizeBatteryTemperatureCelsius(2_000))
    }

    /**
     * 验证无线、交流和USB位掩码使用确定优先级，不把未知值伪装成已知快充类型。
     */
    @Test
    fun chargingPowerSourceFromPluggedValue_mapsKnownSourcesOnly() {
        assertEquals(
            ChargingPowerSource.WIRELESS,
            chargingPowerSourceFromPluggedValue(BatteryManager.BATTERY_PLUGGED_WIRELESS)
        )
        assertEquals(
            ChargingPowerSource.AC,
            chargingPowerSourceFromPluggedValue(BatteryManager.BATTERY_PLUGGED_AC)
        )
        assertEquals(
            ChargingPowerSource.USB,
            chargingPowerSourceFromPluggedValue(BatteryManager.BATTERY_PLUGGED_USB)
        )
        assertEquals(ChargingPowerSource.UNKNOWN, chargingPowerSourceFromPluggedValue(0))
    }
}
