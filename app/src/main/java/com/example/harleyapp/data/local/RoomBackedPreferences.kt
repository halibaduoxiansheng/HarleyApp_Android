package com.example.harleyapp.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 在不改变现有仓库接口的前提下，把字符串JSON升级到Room的SharedPreferences兼容层。
 *
 * 使用方法：
 * 用[create]替换原来的context.getSharedPreferences调用，其余getString、edit、监听器代码无需改变。
 * 字符串优先从Room读取，每次写入同时保留SharedPreferences兼容副本；布尔、整数、长整数和
 * StringSet仍保存在SharedPreferences，因为这些小型设置不需要关系数据库查询。
 *
 * @param delegate 原SharedPreferences，用于兼容副本和非字符串设置。
 * @param namespace 稳定模块名称。
 * @param store Room本地文档存储。
 */
class RoomBackedPreferences private constructor(
    private val delegate: SharedPreferences,
    private val namespace: String,
    private val store: LocalDocumentStore
) : SharedPreferences {

    init {
        // 首次创建时只复制Room中尚不存在的字符串，迁移失败也保留原文件供继续读取。
        store.migrateStrings(namespace, delegate.all)
    }

    /** @return 合并非字符串设置和Room字符串后的完整只读快照。 */
    override fun getAll(): Map<String, *> {
        return delegate.all.toMutableMap().apply {
            store.getNamespace(namespace).forEach { document ->
                put(document.key, document.value)
            }
        }
    }

    /**
     * 读取字符串；Room尚无数据时回退到迁移前兼容副本。
     *
     * @param key 键名。
     * @param defValue 默认值。
     * @return 已保存字符串或默认值。
     */
    override fun getString(key: String, defValue: String?): String? {
        return store.getString(namespace, key) ?: delegate.getString(key, defValue)
    }

    /** @return 原SharedPreferences保存的字符串集合。 */
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        return delegate.getStringSet(key, defValues)
    }

    /** @return 原SharedPreferences保存的整数。 */
    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    /** @return 原SharedPreferences保存的长整数。 */
    override fun getLong(key: String, defValue: Long): Long = delegate.getLong(key, defValue)

    /** @return 原SharedPreferences保存的浮点数。 */
    override fun getFloat(key: String, defValue: Float): Float = delegate.getFloat(key, defValue)

    /** @return 原SharedPreferences保存的布尔值。 */
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return delegate.getBoolean(key, defValue)
    }

    /** @return Room或兼容副本中存在该键时返回true。 */
    override fun contains(key: String): Boolean {
        return store.getString(namespace, key) != null || delegate.contains(key)
    }

    /** @return 同时写入Room和兼容副本的编辑器。 */
    override fun edit(): SharedPreferences.Editor {
        return Editor(
            delegateEditor = delegate.edit(),
            namespace = namespace,
            store = store
        )
    }

    /** 把监听器注册到兼容副本；双写完成后仍会收到原键名变化。 */
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        delegate.registerOnSharedPreferenceChangeListener(listener)
    }

    /** 注销先前注册的SharedPreferences监听器。 */
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** 收集本次编辑涉及的字符串变化，并在提交兼容副本前先更新Room主存储。 */
    private class Editor(
        private val delegateEditor: SharedPreferences.Editor,
        private val namespace: String,
        private val store: LocalDocumentStore
    ) : SharedPreferences.Editor {

        private val stringChanges = linkedMapOf<String, String?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            stringChanges[key] = value
            delegateEditor.putString(key, value)
        }

        override fun putStringSet(
            key: String,
            values: Set<String>?
        ): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.putStringSet(key, values)
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.putInt(key, value)
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.putLong(key, value)
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.putFloat(key, value)
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.putBoolean(key, value)
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            stringChanges[key] = null
            delegateEditor.remove(key)
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
            stringChanges.clear()
            delegateEditor.clear()
        }

        override fun commit(): Boolean {
            val roomSuccess = applyRoomChanges()
            val mirrorSuccess = delegateEditor.commit()
            return roomSuccess && mirrorSuccess
        }

        override fun apply() {
            applyRoomChanges()
            delegateEditor.apply()
        }

        /**
         * 把本次字符串变化提交到Room。
         *
         * @return 全部Room操作成功返回true，否则返回false。
         */
        private fun applyRoomChanges(): Boolean {
            var success = true
            if (clearRequested) {
                success = store.clearNamespace(namespace)
            }
            stringChanges.forEach { (key, value) ->
                val operationSuccess = if (value == null) {
                    store.remove(namespace, key)
                } else {
                    store.putString(namespace, key, value)
                }
                success = success && operationSuccess
            }
            return success
        }
    }

    companion object {
        /**
         * 创建指定模块的Room兼容SharedPreferences。
         *
         * @param context Android上下文。
         * @param preferenceName 原SharedPreferences文件名，同时作为Room命名空间。
         * @return 可直接替代原SharedPreferences的实例。
         */
        fun create(context: Context, preferenceName: String): SharedPreferences {
            val applicationContext = context.applicationContext
            return RoomBackedPreferences(
                delegate = applicationContext.getSharedPreferences(
                    preferenceName,
                    Context.MODE_PRIVATE
                ),
                namespace = preferenceName,
                store = LocalDocumentStore.create(applicationContext)
            )
        }
    }
}
