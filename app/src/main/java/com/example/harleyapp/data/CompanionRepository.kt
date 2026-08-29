package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.afterClaimingTask

/**
 * 保存玩偶分类、累计经验以及当天任务领取状态。
 *
 * 使用方法：
 * App启动后通过getProgress读取状态，再调用claimTask领取每日任务经验；
 * 用户更换玩偶时调用selectCategory。仓库会用日期和任务枚举双重去重，
 * 同一天反复进入页面或反复点击同一功能不会重复增加经验。
 *
 * @param context Android上下文，内部使用Application Context创建SharedPreferences。
 */
class CompanionRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取指定日期下的玩偶成长状态。
     *
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 完整成长状态；保存的任务日期不是今天时，返回的当天任务集合为空。
     */
    fun getProgress(currentEpochDay: Long): CompanionProgress {
        val categoryName = preferences.getString(KEY_CATEGORY, null)
        val category = CompanionCategory.entries.firstOrNull { candidate ->
            candidate.name == categoryName
        } ?: CompanionCategory.FOREST
        val totalExperience = preferences.getInt(KEY_TOTAL_EXPERIENCE, 0)
            .coerceAtLeast(0)
        val storedEpochDay = preferences.getLong(KEY_TASK_EPOCH_DAY, currentEpochDay)
        val completedTasks = if (storedEpochDay == currentEpochDay) {
            preferences.getStringSet(KEY_COMPLETED_TASKS, emptySet())
                .orEmpty()
                .mapNotNullTo(mutableSetOf()) { taskName ->
                    CompanionTask.entries.firstOrNull { task -> task.name == taskName }
                }
        } else {
            emptySet()
        }

        return CompanionProgress(
            category = category,
            totalExperience = totalExperience,
            taskEpochDay = currentEpochDay,
            completedTasks = completedTasks
        )
    }

    /**
     * 领取一项每日任务经验，并在所有常规任务完成后自动追加每日总奖励。
     *
     * @param task 本次成功完成的任务；DAILY_BONUS由仓库自动判断，不应由页面直接传入。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 写入成功后的新状态；任务当天已经领取或写入失败时返回原状态。
     */
    fun claimTask(task: CompanionTask, currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress.afterClaimingTask(task, currentEpochDay)
        if (updatedProgress == currentProgress) {
            return currentProgress
        }

        return if (persistProgress(updatedProgress)) {
            updatedProgress
        } else {
            currentProgress
        }
    }

    /**
     * 更换当前玩偶分类，并保留累计经验和当天任务。
     *
     * @param category 用户选择的新分类。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 写入成功后的新状态；写入失败时返回null。
     */
    fun selectCategory(
        category: CompanionCategory,
        currentEpochDay: Long
    ): CompanionProgress? {
        val updatedProgress = getProgress(currentEpochDay).copy(category = category)
        return if (persistProgress(updatedProgress)) updatedProgress else null
    }

    /**
     * 同步保存成长状态的全部字段，保证经验和任务去重集合一起提交。
     *
     * @param progress 待保存的最新状态。
     *
     * @return SharedPreferences提交成功时返回true，否则返回false。
     */
    private fun persistProgress(progress: CompanionProgress): Boolean {
        val success = preferences.edit()
            .putString(KEY_CATEGORY, progress.category.name)
            .putInt(KEY_TOTAL_EXPERIENCE, progress.totalExperience.coerceAtLeast(0))
            .putLong(KEY_TASK_EPOCH_DAY, progress.taskEpochDay)
            .putStringSet(
                KEY_COMPLETED_TASKS,
                progress.completedTasks.mapTo(mutableSetOf()) { task -> task.name }
            )
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist companion progress")
        }

        return success
    }

    private companion object {
        const val TAG = "CompanionRepository"
        const val PREFERENCE_NAME = "harley_companion"
        const val KEY_CATEGORY = "category"
        const val KEY_TOTAL_EXPERIENCE = "total_experience"
        const val KEY_TASK_EPOCH_DAY = "task_epoch_day"
        const val KEY_COMPLETED_TASKS = "completed_tasks"
    }
}
