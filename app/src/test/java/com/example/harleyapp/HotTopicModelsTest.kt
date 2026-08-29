package com.example.harleyapp

import com.example.harleyapp.model.HotTopicPlatform
import com.example.harleyapp.model.normalizeHotTopicUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证热点原平台链接规范化和官方App映射，不依赖Android设备或真实网络。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class HotTopicModelsTest {

    /**
     * 验证百度查询词中的中文和空格会转换为可安全跳转的ASCII地址。
     *
     * @return 无返回值；编码结果不符时由JUnit报告失败。
     */
    @Test
    fun baiduSearchUrlIsSafelyEncoded() {
        assertEquals(
            "https://www.baidu.com/s?wd=hello%20%E4%B8%96%E7%95%8C",
            normalizeHotTopicUrl(
                platform = HotTopicPlatform.BAIDU,
                rawUrl = "https://www.baidu.com/s?wd=hello 世界",
                title = "hello 世界"
            )
        )
    }

    /**
     * 验证明文HTTP和伪装成微博后缀的恶意域名都不会进入跳转流程。
     *
     * @return 无返回值；任一危险链接被接受时由JUnit报告失败。
     */
    @Test
    fun insecureOrLookalikeDomainsAreRejected() {
        assertNull(
            normalizeHotTopicUrl(
                platform = HotTopicPlatform.WEIBO,
                rawUrl = "http://s.weibo.com/weibo?q=test",
                title = "test"
            )
        )
        assertNull(
            normalizeHotTopicUrl(
                platform = HotTopicPlatform.WEIBO,
                rawUrl = "https://weibo.com.evil.example/topic",
                title = "test"
            )
        )
    }

    /**
     * 验证知乎官方问题地址会保留原目标，而不是退化为第三方中转链接。
     *
     * @return 无返回值；地址被错误改写时由JUnit报告失败。
     */
    @Test
    fun zhihuQuestionKeepsOfficialDestination() {
        val questionUrl = "https://www.zhihu.com/question/123456"

        assertEquals(
            questionUrl,
            normalizeHotTopicUrl(
                platform = HotTopicPlatform.ZHIHU,
                rawUrl = questionUrl,
                title = "测试问题"
            )
        )
    }

    /**
     * 验证四个平台都映射到预期的官方Android包名。
     *
     * @return 无返回值；任一映射变化时由JUnit报告失败并提示复核跳转策略。
     */
    @Test
    fun platformPackagesMatchOfficialAndroidApps() {
        assertEquals("com.sina.weibo", HotTopicPlatform.WEIBO.preferredPackageName)
        assertEquals("com.baidu.searchbox", HotTopicPlatform.BAIDU.preferredPackageName)
        assertEquals("com.zhihu.android", HotTopicPlatform.ZHIHU.preferredPackageName)
        assertEquals("com.ss.android.ugc.aweme", HotTopicPlatform.DOUYIN.preferredPackageName)
    }
}
