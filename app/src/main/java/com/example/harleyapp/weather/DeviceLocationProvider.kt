package com.example.harleyapp.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.model.ApproximateLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 仅在用户打开天气页面后读取一次前台粗略位置。
 *
 * 使用方法：
 * 页面先请求ACCESS_COARSE_LOCATION并确认授权，再调用[getCurrentApproximateLocation]。
 * 本类不申请后台定位、不注册持续监听，也不保存位置历史；返回结果交给天气仓库完成一次查询后即可丢弃。
 *
 * @param context Android上下文，内部使用Application Context取得LocationManager。
 */
class DeviceLocationProvider(context: Context) {

    private val applicationContext = context.applicationContext
    private val locationManager = applicationContext.getSystemService(LocationManager::class.java)

    /**
     * 获取当前粗略位置，超时后允许回退到六小时内的系统缓存位置。
     *
     * @param timeoutMillis 等待系统产生一次定位的最长时间。
     * @return 成功时返回位置；无权限、定位关闭或超时时返回失败。
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentApproximateLocation(
        timeoutMillis: Long = DEFAULT_LOCATION_TIMEOUT_MILLIS
    ): Result<ApproximateLocation> {
        if (!hasCoarseLocationPermission()) {
            return Result.failure(IllegalStateException("Location permission is not granted"))
        }

        val provider = chooseProvider()
            ?: return Result.failure(IllegalStateException("No location provider is enabled"))
        val currentLocation = runCatching {
            withTimeoutOrNull(timeoutMillis) {
                requestSingleLocation(provider)
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to request current location", error)
            null
        }
        val fallback = currentLocation ?: getRecentLastKnownLocation()
        return if (fallback != null) {
            Result.success(fallback.toApproximateLocation())
        } else {
            Result.failure(IllegalStateException("Current location is unavailable"))
        }
    }

    /** @return 已授予前台粗略位置权限时返回true。 */
    fun hasCoarseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** @return 当前可用的低功耗网络定位或GPS提供者；均关闭时返回null。 */
    private fun chooseProvider(): String? {
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).firstOrNull { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    /**
     * 兼容Android 8到最新版本的一次性定位请求。
     *
     * @param provider 已启用的位置提供者名称。
     * @return 系统返回的一次位置；协程取消时立即注销旧版监听器。
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(provider: String): Location? {
        return suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    applicationContext.mainExecutor
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            } else {
                lateinit var listener: LocationListener
                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(listener)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    @Deprecated("Deprecated by Android platform")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
    }

    /** @return 六小时内最新的系统缓存位置；没有可用缓存时返回null。 */
    @SuppressLint("MissingPermission")
    private fun getRecentLastKnownLocation(): Location? {
        val threshold = System.currentTimeMillis() - MAX_LAST_LOCATION_AGE_MILLIS
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { location ->
            location.time >= threshold
        }.maxByOrNull { location ->
            location.time
        }
    }

    /** @return 去除Android对象依赖的不可变粗略位置模型。 */
    private fun Location.toApproximateLocation(): ApproximateLocation {
        return ApproximateLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy.coerceAtLeast(0f),
            obtainedAtMillis = time.coerceAtLeast(0L)
        )
    }

    private companion object {
        const val TAG = "DeviceLocationProvider"
        const val DEFAULT_LOCATION_TIMEOUT_MILLIS = 12_000L
        const val MAX_LAST_LOCATION_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
