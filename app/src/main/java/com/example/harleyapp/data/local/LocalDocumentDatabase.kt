package com.example.harleyapp.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * Room中一条可独立更新的本地JSON文档。
 *
 * 使用方法：
 * 数据仓库不直接创建本对象，而是通过[RoomBackedPreferences]把原有字符串数据迁移到这里。
 * [namespace]对应原SharedPreferences文件名，[key]对应原键名，因此迁移不改变已有JSON结构。
 *
 * @param namespace 数据所属模块，例如harley_ledger。
 * @param key 模块内部稳定键名，例如entries。
 * @param value 完整JSON文本或普通字符串，只保存在本机Room数据库。
 * @param updatedAtMillis 最近成功写入的Unix毫秒时间戳。
 */
@Entity(
    tableName = "local_documents",
    primaryKeys = ["namespace", "key"]
)
data class LocalDocumentEntity(
    val namespace: String,
    val key: String,
    val value: String,
    val updatedAtMillis: Long
)

/**
 * 提供本地文档的同步增删改查接口。
 *
 * 现有仓库接口均为同步函数，且数据量受应用自身上限控制，所以这里保留同步DAO以兼容旧调用。
 * 后续如果页面统一改为Flow，可在不改变数据库格式的情况下继续增加异步查询接口。
 */
@Dao
interface LocalDocumentDao {

    /**
     * 读取指定模块中的一条文档。
     *
     * @param namespace 模块命名空间。
     * @param key 文档键名。
     * @return 找到时返回文档，否则返回null。
     */
    @Query(
        "SELECT * FROM local_documents " +
            "WHERE namespace = :namespace AND `key` = :key LIMIT 1"
    )
    fun get(namespace: String, key: String): LocalDocumentEntity?

    /**
     * 读取一个模块的全部文档。
     *
     * @param namespace 模块命名空间。
     * @return 按键名排序的文档列表。
     */
    @Query("SELECT * FROM local_documents WHERE namespace = :namespace ORDER BY `key`")
    fun getNamespace(namespace: String): List<LocalDocumentEntity>

    /**
     * 读取数据库中的全部文档，供本地备份使用。
     *
     * @return 按模块和键名排序的完整文档列表。
     */
    @Query("SELECT * FROM local_documents ORDER BY namespace, `key`")
    fun getAll(): List<LocalDocumentEntity>

    /**
     * 插入或覆盖一条文档。
     *
     * @param document 需要保存的完整文档。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(document: LocalDocumentEntity)

    /**
     * 批量插入或覆盖文档。
     *
     * @param documents 需要保存的文档集合。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(documents: List<LocalDocumentEntity>)

    /**
     * 删除指定文档。
     *
     * @param namespace 模块命名空间。
     * @param key 文档键名。
     */
    @Query("DELETE FROM local_documents WHERE namespace = :namespace AND `key` = :key")
    fun delete(namespace: String, key: String)

    /**
     * 删除一个模块的全部文档。
     *
     * @param namespace 模块命名空间。
     */
    @Query("DELETE FROM local_documents WHERE namespace = :namespace")
    fun deleteNamespace(namespace: String)

    /** 删除数据库中的全部文档，仅供经过确认的备份恢复事务调用。 */
    @Query("DELETE FROM local_documents")
    fun deleteAll()

    /**
     * 原子替换全部文档，防止导入中途只恢复一半数据。
     *
     * @param documents 已通过格式和完整性校验的新文档。
     */
    @Transaction
    fun replaceAll(documents: List<LocalDocumentEntity>) {
        deleteAll()
        if (documents.isNotEmpty()) {
            upsertAll(documents)
        }
    }
}

/**
 * Harley App本地结构化数据数据库。
 *
 * 使用方法：
 * 调用[getInstance]取得进程内单例，再通过[documents]访问文档DAO。数据库位于应用私有目录，
 * 不启用云同步，也不包含热点或天气网络缓存以外的远程数据。
 */
@Database(
    entities = [LocalDocumentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LocalDocumentDatabase : RoomDatabase() {

    /** @return 本地文档DAO。 */
    abstract fun documents(): LocalDocumentDao

    companion object {
        private const val DATABASE_NAME = "harley_local_documents.db"

        @Volatile
        private var instance: LocalDocumentDatabase? = null

        /**
         * 获取整个进程共享的Room数据库。
         *
         * @param context 任意Android上下文，内部只保存Application Context。
         * @return 已初始化的数据库单例。
         */
        fun getInstance(context: Context): LocalDocumentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalDocumentDatabase::class.java,
                    DATABASE_NAME
                )
                    // 兼容现有同步仓库接口；当前每个键只保存一个受大小限制的本地JSON文档。
                    .allowMainThreadQueries()
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
        }
    }
}
