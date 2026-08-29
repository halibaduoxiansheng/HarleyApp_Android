package com.example.harleyapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExerciseDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 动态运动项目和每日记录仓库的实机增删改查测试。
 *
 * 使用方法：
 * 连接Android设备后执行：
 * `gradlew connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.example.harleyapp.FitnessRepositoryCrudTest`。
 * 测试使用目标App的设备保护存储区，与正式App日常使用的凭据保护存储区隔离；
 * 测试日期固定放到当前日期后100年，并在finally中删除专用项目和记录。
 */
@RunWith(AndroidJUnit4::class)
class FitnessRepositoryCrudTest {

    /**
     * 在真实Android SharedPreferences中验证项目和记录的新增、查询、修改、删除及区间查询。
     *
     * @return 无返回值；任一步骤结果不符合预期时由JUnit报告失败。
     */
    @Test
    fun exerciseDefinitionAndDailyRecordCrudWork() {
        val testContext = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .createDeviceProtectedStorageContext()
        val repository = FitnessRepository(testContext)
        val testEpochDay = LocalDate.now().plusYears(TEST_DATE_OFFSET_YEARS).toEpochDay()
        val definition = FitnessExerciseDefinition(
            id = TEST_EXERCISE_ID,
            name = "测试跳绳",
            unit = "次",
            dailyGoal = 100,
            quickIncrement = 20
        )

        repository.deleteRecord(testEpochDay)
        repository.deleteExerciseDefinition(TEST_EXERCISE_ID)

        try {
            // 项目新增和查询：真实写入后必须能从新的仓库读取结果。
            assertEquals(definition, repository.upsertExerciseDefinition(definition))
            assertEquals(
                definition,
                FitnessRepository(testContext).getExerciseDefinitions().firstOrNull {
                    it.id == TEST_EXERCISE_ID
                }
            )

            // 项目修改：稳定id不变，名称、目标和快速增加量应被覆盖。
            val editedDefinition = definition.copy(
                name = "测试跳绳已修改",
                dailyGoal = 160,
                quickIncrement = 40
            )
            assertEquals(
                editedDefinition,
                repository.upsertExerciseDefinition(editedDefinition)
            )

            val created = DailyFitnessRecord(
                dateEpochDay = testEpochDay,
                items = listOf(editedDefinition.toRecordItem(count = 80))
            )

            // 记录新增、查询和区间查询。
            assertNotNull(repository.upsertRecord(created))
            assertEquals(created, repository.getRecord(testEpochDay))
            assertEquals(
                listOf(created),
                repository.getRecordsInRange(testEpochDay, testEpochDay)
            )

            // 记录修改：同一日期覆盖旧完成量，不产生重复日期。
            val updated = created.copy(
                items = listOf(editedDefinition.toRecordItem(count = 160))
            )
            assertEquals(updated, repository.upsertRecord(updated))
            assertTrue(repository.getRecord(testEpochDay).isComplete())

            // 项目删除后不再出现在当前定义，但历史记录快照继续保留。
            assertTrue(repository.deleteExerciseDefinition(TEST_EXERCISE_ID))
            assertNull(
                repository.getExerciseDefinitions().firstOrNull {
                    it.id == TEST_EXERCISE_ID
                }
            )
            assertEquals(160, repository.getRecord(testEpochDay).countFor(TEST_EXERCISE_ID))

            // 记录删除后返回零完成量临时记录，区间明细也不再包含该日期。
            assertTrue(repository.deleteRecord(testEpochDay))
            assertFalse(repository.getRecord(testEpochDay).hasRecordedActivity())
            assertTrue(repository.getRecordsInRange(testEpochDay, testEpochDay).isEmpty())
        } finally {
            repository.deleteRecord(testEpochDay)
            repository.deleteExerciseDefinition(TEST_EXERCISE_ID)
        }
    }

    private companion object {
        const val TEST_DATE_OFFSET_YEARS = 100L
        const val TEST_EXERCISE_ID = "instrumentation_test_rope"
    }
}
