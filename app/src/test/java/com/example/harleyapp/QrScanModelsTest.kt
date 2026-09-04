package com.example.harleyapp

import com.example.harleyapp.model.QrScanContentType
import com.example.harleyapp.model.normalizeSafeWebUrl
import com.example.harleyapp.model.parseQrScanContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证二维码内容分类和网页安全边界，不依赖相机或Android设备。
 *
 * 使用方法：
 * 在项目根目录运行`gradlew :app:testDebugUnitTest`，可在实机扫码前先发现危险协议被误放行、
 * Wi-Fi转义解析错误或普通文本丢失等问题。
 */
class QrScanModelsTest {

    /** 合法HTTPS链接必须保留，并允许页面显示显式打开按钮。 */
    @Test
    fun httpsLinkIsAcceptedAfterValidation() {
        val content = parseQrScanContent("https://www.gov.cn/zhengce/index.htm")

        assertEquals(QrScanContentType.WEB_LINK, content?.type)
        assertEquals("https://www.gov.cn/zhengce/index.htm", content?.safeWebUrl)
    }

    /** 自定义协议、用户凭据和缺少主机名的网址不能交给浏览器自动处理。 */
    @Test
    fun unsafeLinksAreRejected() {
        assertNull(normalizeSafeWebUrl("intent://scan/#Intent;scheme=test;end"))
        assertNull(normalizeSafeWebUrl("file:///sdcard/private.txt"))
        assertNull(normalizeSafeWebUrl("https://user:password@example.com/private"))
        assertNull(normalizeSafeWebUrl("https:///missing-host"))
    }

    /** Wi-Fi二维码中的转义分号和反斜杠必须正确恢复，且不会被当成网页。 */
    @Test
    fun wifiQrCodeIsParsedWithoutExecutingConnection() {
        val content = parseQrScanContent("WIFI:T:WPA;S:Home\\;Study;P:abc\\\\123;H:true;;")

        assertEquals(QrScanContentType.WIFI, content?.type)
        assertTrue(content?.detail.orEmpty().contains("网络：Home;Study"))
        assertTrue(content?.detail.orEmpty().contains("密码：abc\\123"))
        assertTrue(content?.detail.orEmpty().contains("隐藏网络：是"))
        assertNull(content?.safeWebUrl)
    }

    /** 普通文本必须原样展示，空白二维码则不生成误导性的结果卡片。 */
    @Test
    fun plainTextIsPreservedAndBlankContentIsIgnored() {
        val content = parseQrScanContent("  图书馆三楼  ")

        assertEquals(QrScanContentType.TEXT, content?.type)
        assertEquals("图书馆三楼", content?.rawValue)
        assertNull(parseQrScanContent("   "))
    }
}
