package com.example.harleyapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.model.DailyFitnessRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 健身记录本地仓库的实机增删改查测试。
 *
 * 使用方法：
 * 连接Android设备后执行：
 * `gradlew connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.example.harleyapp.FitnessRepositoryCrudTest`。
 * 测试使用目标App的设备保护存储区，与正式App日常使用的凭据保护存储区完全隔离；
 * 开始与结束时还会删除专用测试日期的数据，因此不会污染用户真实运动记录。
 */
@RunWith(AndroidJUnit4::class)
class FitnessRepositoryCrudTest {

    /**
     * 在真实Android存储环境中依次验证新增、查询、修改和删除接口。
     *
     * 使用方法：
     * 由AndroidJUnitRunner自动调用，不需要页面或人工输入。测试日期固定取当前日期后100年，
     * 避免与普通测试数据冲突；无论断言是否成功，finally都会执行清理。
     *
     * @return 无返回值；任一步骤结果不符合预期时由JUnit报告失败。
     */
    @Test
    fun createReadUpdateAndDeleteRecordWork() {
        val testContext = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .createDeviceProtectedStorageContext()
        val repository = FitnessRepository(testContext)
        val testEpochDay = LocalDate.now().plusYears(TEST_DATE_OFFSET_YEARS).toEpochDay()

        repository.deleteRecord(testEpochDay)

        try {
            val created = DailyFitnessRecord(
                dateEpochDay = testEpochDay,
                pushUps = 12,
                sitUps = 18,
                steps = 2_345,
                pushUpGoal = 30,
                sitUpGoal = 50,
                stepGoal = 8_000
            )

            // 新增：日期尚不存在时，upsertRecord应创建一条完整记录。
            assertNotNull(repository.upsertRecord(created))

            // 查询：重新从SharedPreferences读取，确保不是只验证内存中的对象。
            val queried = repository.getRecord(testEpochDay)
            assertEquals(created, queried)
            assertTrue(queried.hasRecordedActivity())

            // 修改：同一日期再次写入时，应覆盖旧运动量而不是产生重复记录。
            val updated = created.copy(
                pushUps = 30,
                sitUps = 50,
                steps = 8_100
            )
            assertEquals(updated, repository.upsertRecord(updated))
            assertEquals(updated, repository.getRecord(testEpochDay))
            assertTrue(repository.getRecord(testEpochDay).isComplete())

            // 删除：删除后再次查询会得到零运动量占位记录，表示持久化记录已经不存在。
            assertTrue(repository.deleteRecord(testEpochDay))
            val afterDelete = repository.getRecord(testEpochDay)
            assertEquals(testEpochDay, afterDelete.dateEpochDay)
            assertFalse(afterDelete.hasRecordedActivity())
        } finally {
            repository.deleteRecord(testEpochDay)
        }
    }

    private companion object {
        const val TEST_DATE_OFFSET_YEARS = 100L
    }
}
