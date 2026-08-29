package com.example.harleyapp.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * 封装Android低功耗累计计步传感器的注册和释放。
 *
 * 使用方法：
 * 先调用isSupported判断手机是否提供TYPE_STEP_COUNTER；获得ACTIVITY_RECOGNITION权限后调用start，
 * 页面离开或组件销毁时必须调用stop。回调值是手机开机以来的累计步数，不可直接当作当天步数，
 * 应交给FitnessRepository.syncSensorSteps转换为安全增量。
 *
 * @param context Android上下文，内部只保留Application Context关联的系统服务。
 */
class StepCounterMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(
        Context.SENSOR_SERVICE
    ) as SensorManager
    private val stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var onStepTotalChanged: ((Long) -> Unit)? = null

    /**
     * 判断当前手机是否提供累计计步传感器。
     *
     * @return 存在TYPE_STEP_COUNTER传感器返回true，否则返回false。
     */
    fun isSupported(): Boolean {
        return stepCounterSensor != null
    }

    /**
     * 开始监听系统累计步数。
     *
     * @param onStepTotalChanged 每次传感器更新时回调，参数为开机以来累计步数。
     *
     * @return 注册成功返回true；无传感器、权限不足或系统拒绝注册时返回false。
     */
    fun start(onStepTotalChanged: (Long) -> Unit): Boolean {
        val sensor = stepCounterSensor ?: return false
        stop()
        this.onStepTotalChanged = onStepTotalChanged

        return try {
            val registered = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            if (!registered) {
                this.onStepTotalChanged = null
                Log.w(TAG, "Step counter listener registration was rejected")
            }
            registered
        } catch (error: SecurityException) {
            this.onStepTotalChanged = null
            Log.e(TAG, "Activity recognition permission is missing", error)
            false
        }
    }

    /**
     * 停止监听并释放页面持有的传感器回调。
     *
     * @return 无返回值。
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        onStepTotalChanged = null
    }

    /**
     * 接收系统传感器事件并转发合法累计值。
     *
     * @param event Android系统传入的传感器事件。
     *
     * @return 无返回值。
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) {
            return
        }

        val total = event.values.firstOrNull()?.toLong() ?: return
        if (total >= 0L) {
            onStepTotalChanged?.invoke(total)
        }
    }

    /**
     * 接收传感器精度变化。累计计步只关心总数，因此无需额外处理。
     *
     * @param sensor 精度发生变化的传感器。
     * @param accuracy 系统报告的新精度等级。
     *
     * @return 无返回值。
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "StepCounterMonitor"
    }
}
