package com.example.harleyapp.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt

/**
 * Android公开接口能够区分的供电来源。
 *
 * 使用方法：
 * [ChargingBatteryReader.read]会根据`BatteryManager.EXTRA_PLUGGED`自动生成该枚举，界面只需
 * 读取[displayName]即可展示，不应根据电流大小猜测快充协议或充电器功率。
 *
 * @param displayName 面向用户显示的中文供电方式名称。
 */
enum class ChargingPowerSource(val displayName: String) {
    USB("USB供电"),
    AC("充电器供电"),
    WIRELESS("无线充电"),
    DOCK("充电底座"),
    UNKNOWN("未知电源")
}

/**
 * 一次完整的手机电池与充电状态快照。
 *
 * @param isConnected 是否已经连接外部电源；连接但因高温、满电等原因暂停充电时仍为true。
 * @param isCharging Android当前是否判定电池正在增加电量或已经充满。
 * @param isFull 电池状态是否为已充满。
 * @param levelPercent 剩余电量百分比；系统数据无效时为null。
 * @param currentMilliAmps 电池端瞬时净电流，单位mA；正值表示流入电池，负值表示电池放电。
 * @param voltageVolts 电池当前电压，单位V；该值不是USB或快充适配器的输出电压。
 * @param temperatureCelsius 电池温度，单位摄氏度；该值不是CPU、外壳或充电器温度。
 * @param powerSource Android能够识别的外部供电来源。
 */
data class ChargingBatterySnapshot(
    val isConnected: Boolean,
    val isCharging: Boolean,
    val isFull: Boolean,
    val levelPercent: Int?,
    val currentMilliAmps: Float?,
    val voltageVolts: Float?,
    val temperatureCelsius: Float?,
    val powerSource: ChargingPowerSource
) {

    companion object {

        /**
         * 创建系统尚未返回有效电池数据时的安全空快照。
         *
         * @return 所有数值均为不可用、供电状态为未连接的快照。
         */
        fun unavailable(): ChargingBatterySnapshot {
            return ChargingBatterySnapshot(
                isConnected = false,
                isCharging = false,
                isFull = false,
                levelPercent = null,
                currentMilliAmps = null,
                voltageVolts = null,
                temperatureCelsius = null,
                powerSource = ChargingPowerSource.UNKNOWN
            )
        }
    }
}

/**
 * 通过Android公开BatteryManager与粘性电池广播读取实时充电数据。
 *
 * 使用方法：
 * 页面或充电监控服务按需调用[read]。本类不注册长期广播、不持有Activity，也不会读取需要
 * Root权限的`/sys`节点；厂商未开放某项数据时，对应字段保持null而不是生成估算值。
 *
 * @param context Android上下文，内部转换为Application Context避免持有页面实例。
 */
class ChargingBatteryReader(context: Context) {

    private val applicationContext = context.applicationContext
    private val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)

    /**
     * 读取当前电池状态、电量、电流、电压、温度和供电方式。
     *
     * @return Android返回有效粘性电池广播时生成实时快照；广播缺失时返回安全空快照。
     */
    fun read(): ChargingBatterySnapshot {
        val batteryIntent = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ChargingBatterySnapshot.unavailable()
        val status = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, INVALID_INT_VALUE)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, INVALID_INT_VALUE)
        val rawVoltage = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE,
            INVALID_INT_VALUE
        )
        val rawTemperature = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_TEMPERATURE,
            INVALID_INT_VALUE
        )
        val rawCurrent = batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        )

        return ChargingBatterySnapshot(
            isConnected = plugged != 0,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            isFull = status == BatteryManager.BATTERY_STATUS_FULL,
            levelPercent = normalizeBatteryPercentage(level, scale),
            currentMilliAmps = normalizeBatteryCurrentMilliAmps(rawCurrent),
            voltageVolts = normalizeBatteryVoltageVolts(rawVoltage),
            temperatureCelsius = normalizeBatteryTemperatureCelsius(rawTemperature),
            powerSource = chargingPowerSourceFromPluggedValue(plugged)
        )
    }
}

/**
 * 把Android电池电量与量程转换为0到100的整数百分比。
 *
 * @param level 系统广播中的当前电量刻度。
 * @param scale 系统广播中的满量程刻度，必须大于0。
 * @return 合法百分比；参数无效或电量超出量程时返回null。
 */
internal fun normalizeBatteryPercentage(level: Int, scale: Int): Int? {
    if (scale <= 0 || level < 0 || level > scale) {
        return null
    }
    return (level * 100f / scale.toFloat()).roundToInt().coerceIn(0, 100)
}

/**
 * 把BatteryManager规定的微安瞬时电流转换为毫安。
 *
 * @param currentMicroAmps Android返回的电池端瞬时净电流，单位uA。
 * @return 毫安电流；系统不支持该属性或返回明显越界值时返回null。
 */
internal fun normalizeBatteryCurrentMilliAmps(currentMicroAmps: Int): Float? {
    if (currentMicroAmps == Int.MIN_VALUE ||
        currentMicroAmps.toLong() !in -MAX_ABSOLUTE_CURRENT_MICRO_AMPS..
            MAX_ABSOLUTE_CURRENT_MICRO_AMPS
    ) {
        return null
    }
    return currentMicroAmps / 1_000f
}

/**
 * 把电池广播中的毫伏数转换为伏特。
 *
 * @param voltageMilliVolts Android返回的电池电压，单位mV。
 * @return 合理手机电池范围内的伏特值；系统未提供或明显异常时返回null。
 */
internal fun normalizeBatteryVoltageVolts(voltageMilliVolts: Int): Float? {
    if (voltageMilliVolts !in MIN_BATTERY_VOLTAGE_MILLIVOLTS..
        MAX_BATTERY_VOLTAGE_MILLIVOLTS
    ) {
        return null
    }
    return voltageMilliVolts / 1_000f
}

/**
 * 把电池广播中的十分之一摄氏度转换为摄氏度。
 *
 * @param temperatureTenthsCelsius Android返回的电池温度原始整数。
 * @return 合理手机电池范围内的摄氏温度；系统未提供或明显异常时返回null。
 */
internal fun normalizeBatteryTemperatureCelsius(temperatureTenthsCelsius: Int): Float? {
    if (temperatureTenthsCelsius !in MIN_BATTERY_TEMPERATURE_TENTHS..
        MAX_BATTERY_TEMPERATURE_TENTHS
    ) {
        return null
    }
    return temperatureTenthsCelsius / 10f
}

/**
 * 把Android供电位掩码转换为稳定的界面枚举。
 *
 * @param pluggedValue `BatteryManager.EXTRA_PLUGGED`返回的位掩码。
 * @return 已识别供电方式；未连接或厂商使用未知值时返回[ChargingPowerSource.UNKNOWN]。
 */
internal fun chargingPowerSourceFromPluggedValue(pluggedValue: Int): ChargingPowerSource {
    return when {
        pluggedValue and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 ->
            ChargingPowerSource.WIRELESS
        pluggedValue and BatteryManager.BATTERY_PLUGGED_DOCK != 0 ->
            ChargingPowerSource.DOCK
        pluggedValue and BatteryManager.BATTERY_PLUGGED_AC != 0 ->
            ChargingPowerSource.AC
        pluggedValue and BatteryManager.BATTERY_PLUGGED_USB != 0 ->
            ChargingPowerSource.USB
        else -> ChargingPowerSource.UNKNOWN
    }
}

private const val INVALID_INT_VALUE = Int.MIN_VALUE
private const val MAX_ABSOLUTE_CURRENT_MICRO_AMPS = 30_000_000L
private const val MIN_BATTERY_VOLTAGE_MILLIVOLTS = 2_000
private const val MAX_BATTERY_VOLTAGE_MILLIVOLTS = 6_000
private const val MIN_BATTERY_TEMPERATURE_TENTHS = -400
private const val MAX_BATTERY_TEMPERATURE_TENTHS = 1_000
