package com.example.harleyapp.data.local

import android.content.Context
import android.util.Log

/**
 * 对Room文档DAO进行异常隔离和旧数据迁移的本地存储门面。
 *
 * 使用方法：
 * 通过[create]创建实例，再由[RoomBackedPreferences]调用读取、写入和删除接口。
 * 所有异常只记录英文日志并返回失败，不会删除原SharedPreferences兼容副本。
 *
 * @param dao Room生成的本地文档DAO。
 */
class LocalDocumentStore private constructor(
    private val dao: LocalDocumentDao
) {

    /**
     * 读取指定字符串文档。
     *
     * @param namespace 模块命名空间。
     * @param key 文档键名。
     * @return 找到时返回内容，失败或不存在时返回null。
     */
    fun getString(namespace: String, key: String): String? {
        return runCatching {
            dao.get(namespace, key)?.value
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read local document", error)
            null
        }
    }

    /**
     * 保存字符串文档。
     *
     * @param namespace 模块命名空间。
     * @param key 文档键名。
     * @param value 需要保存的完整内容。
     * @return Room写入成功返回true，否则返回false。
     */
    fun putString(namespace: String, key: String, value: String): Boolean {
        return runCatching {
            dao.upsert(
                LocalDocumentEntity(
                    namespace = namespace,
                    key = key,
                    value = value,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to persist local document", error)
            false
        }
    }

    /**
     * 删除一条字符串文档。
     *
     * @param namespace 模块命名空间。
     * @param key 文档键名。
     * @return 删除命令成功执行返回true，否则返回false。
     */
    fun remove(namespace: String, key: String): Boolean {
        return runCatching {
            dao.delete(namespace, key)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to delete local document", error)
            false
        }
    }

    /**
     * 清除一个模块的Room文档。
     *
     * @param namespace 模块命名空间。
     * @return 清除成功返回true，否则返回false。
     */
    fun clearNamespace(namespace: String): Boolean {
        return runCatching {
            dao.deleteNamespace(namespace)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to clear local document namespace", error)
            false
        }
    }

    /**
     * 读取一个模块的全部Room文档。
     *
     * @param namespace 模块命名空间。
     * @return 成功时返回完整列表，失败时返回空列表。
     */
    fun getNamespace(namespace: String): List<LocalDocumentEntity> {
        return runCatching {
            dao.getNamespace(namespace)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to list local document namespace", error)
            emptyList()
        }
    }

    /**
     * 把SharedPreferences中的旧字符串复制到尚无对应文档的Room键中。
     *
     * @param namespace 模块命名空间。
     * @param values 原SharedPreferences的完整键值集合；只迁移String类型。
     * @return 所有需要迁移的字符串均成功写入时返回true。
     */
    fun migrateStrings(namespace: String, values: Map<String, *>): Boolean {
        return values.entries
            .filter { entry -> entry.value is String }
            .all { entry ->
                val key = entry.key
                val value = entry.value as String
                if (getString(namespace, key) != null) {
                    true
                } else {
                    putString(namespace, key, value)
                }
            }
    }

    /**
     * 导出全部Room文档，供本地备份生成器使用。
     *
     * @return 按模块和键名排序的文档；失败时返回空列表。
     */
    fun snapshotAll(): List<LocalDocumentEntity> {
        return runCatching {
            dao.getAll()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to snapshot local documents", error)
            emptyList()
        }
    }

    /**
     * 原子替换全部Room文档，仅在备份完整性校验通过后调用。
     *
     * @param documents 需要恢复的完整文档集合。
     * @return 事务成功返回true，否则返回false且Room会回滚。
     */
    fun replaceAll(documents: List<LocalDocumentEntity>): Boolean {
        return runCatching {
            dao.replaceAll(documents)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to restore local documents", error)
            false
        }
    }

    companion object {
        private const val TAG = "LocalDocumentStore"

        /**
         * 创建本地文档存储门面。
         *
         * @param context Android上下文。
         * @return 使用进程内Room单例的存储对象。
         */
        fun create(context: Context): LocalDocumentStore {
            return LocalDocumentStore(
                LocalDocumentDatabase.getInstance(context).documents()
            )
        }
    }
}
