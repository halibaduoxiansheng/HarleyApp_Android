package com.example.harleyapp.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 在同一应用进程内通知界面重新读取外部入口写入的网站收藏。
 *
 * 使用方法：
 * 浏览器悬浮球通过[WebsiteRepository.saveBookmarkedPage]保存成功后调用[notifyChanged]；
 * HarleyApp界面持续收集[changes]并从仓库读取最新完整收藏。通知只携带“发生变化”这一事实，
 * 不复制标题、网址或收藏列表，避免在多个内存副本间传播用户数据。
 */
object WebsiteLibraryChangeNotifier {

    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 外部收藏成功后的只读变化事件流。 */
    val changes = mutableChanges.asSharedFlow()

    /**
     * 发送一次网站收藏已经变化的进程内通知。
     *
     * @return 有活动收集者或缓冲区接受事件时返回true；界面尚未创建时返回false也不影响持久化，
     * 因为界面首次启动会直接读取仓库。
     */
    fun notifyChanged(): Boolean {
        return mutableChanges.tryEmit(Unit)
    }
}
