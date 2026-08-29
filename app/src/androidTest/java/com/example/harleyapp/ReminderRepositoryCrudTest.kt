package com.example.harleyapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.model.ScheduledReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通用定时通知仓库的真实Android SharedPreferences增删改查测试。
 *
 * 使用方法：
 * 连接Android设备后执行：
 * `gradlew connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.example.harleyapp.ReminderRepositoryCrudTest`。
 * 测试使用设备保护存储，与正式App的凭据保护存储隔离，并在finally中清理测试计划。
 */
@RunWith(AndroidJUnit4::class)
class ReminderRepositoryCrudTest {

    /**
     * 验证通知计划新增、重新查询、按稳定id修改以及删除后的查询结果。
     *
     * @return 无返回值；任一步骤数据不一致时由JUnit报告失败。
     */
    @Test
    fun scheduledReminderCrudWorks() {
        val testContext = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .createDeviceProtectedStorageContext()
        val repository = ReminderRepository(testContext)
        cleanupTestReminders(repository)

        try {
            val originalTriggerAtMillis = System.currentTimeMillis() + ONE_DAY_MILLIS
            val created = repository.upsertReminder(
                ScheduledReminder(
                    id = 0L,
                    content = "  $TEST_CONTENT_PREFIX 新增  ",
                    nextTriggerAtMillis = originalTriggerAtMillis,
                    repeatIntervalDays = 2
                )
            )

            // 新增和查询：仓库生成正数id、清理首尾空格，并可由新实例再次读取。
            assertNotNull(created)
            assertTrue(created!!.id > 0L)
            assertEquals("$TEST_CONTENT_PREFIX 新增", created.content)
            val queried = ReminderRepository(testContext).getReminder(created.id)
            assertEquals(created, queried)

            // 修改：保留同一id和创建时间，覆盖内容、日期以及重复间隔，不产生第二条计划。
            val edited = created.copy(
                content = "$TEST_CONTENT_PREFIX 已修改",
                nextTriggerAtMillis = originalTriggerAtMillis + ONE_DAY_MILLIS,
                repeatIntervalDays = 5
            )
            val savedEdit = repository.upsertReminder(edited)
            assertEquals(edited, savedEdit)
            assertEquals(
                1,
                repository.getReminders().count { reminder ->
                    reminder.content.startsWith(TEST_CONTENT_PREFIX)
                }
            )
            assertNotEquals(originalTriggerAtMillis, savedEdit!!.nextTriggerAtMillis)

            // 删除和删除后查询：首次和重复删除都应成功，计划必须不再可查。
            assertTrue(repository.deleteReminder(created.id))
            assertNull(repository.getReminder(created.id))
            assertTrue(repository.deleteReminder(created.id))
        } finally {
            cleanupTestReminders(repository)
        }
    }

    /**
     * 删除隔离存储中可能由上次异常中断遗留的测试计划。
     *
     * @param repository 当前测试使用的通知仓库。
     *
     * @return 无返回值。
     */
    private fun cleanupTestReminders(repository: ReminderRepository) {
        repository.getReminders()
            .filter { reminder ->
                reminder.content.startsWith(TEST_CONTENT_PREFIX)
            }
            .forEach { reminder ->
                repository.deleteReminder(reminder.id)
            }
    }

    private companion object {
        const val TEST_CONTENT_PREFIX = "[ReminderCrudTest]"
        const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
