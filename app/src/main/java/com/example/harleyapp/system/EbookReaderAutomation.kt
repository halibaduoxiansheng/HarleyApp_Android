package com.example.harleyapp.system

/** 自动翻页允许设置的最短单页停留时间，单位为秒。 */
internal const val MIN_EBOOK_AUTO_PAGE_INTERVAL_SECONDS = 5

/** 自动翻页允许设置的最长单页停留时间，单位为秒。 */
internal const val MAX_EBOOK_AUTO_PAGE_INTERVAL_SECONDS = 60

/** 首次打开阅读器时采用的默认单页停留时间，单位为秒。 */
internal const val DEFAULT_EBOOK_AUTO_PAGE_INTERVAL_SECONDS = 15

/**
 * 判断当前阅读器状态是否允许启动一次定时自动翻页倒计时。
 *
 * 使用方法：
 * 阅读器把所有会暂停倒计时的界面状态传入本函数；只有返回`true`时才启动延时任务。任一参数
 * 变化都会让Compose取消旧任务并重新判断，因此连续朗读不会与定时器同时推动页码。
 *
 * @param enabled 用户是否已经开启定时自动翻页。
 * @param continuousReading 是否正在连续朗读；朗读期间必须由TTS完成回调负责翻页。
 * @param readerForeground 阅读器所属Activity当前是否处于前台RESUMED状态。
 * @param overlayVisible 是否正在显示阅读设置、目录、笔记或其他遮挡正文的弹层。
 * @param pageScrollInProgress 用户或程序是否仍在执行翻页动画。
 * @param contentReady 当前页正文或PDF页面是否已经具备可阅读条件。
 * @param currentPage 当前零基页码。
 * @param pageCount 当前书籍总页数。
 * @return 所有条件都满足且当前不是最后一页时返回`true`，否则返回`false`。
 */
internal fun shouldScheduleEbookTimedPageTurn(
    enabled: Boolean,
    continuousReading: Boolean,
    readerForeground: Boolean,
    overlayVisible: Boolean,
    pageScrollInProgress: Boolean,
    contentReady: Boolean,
    currentPage: Int,
    pageCount: Int
): Boolean {
    return enabled &&
        !continuousReading &&
        readerForeground &&
        !overlayVisible &&
        !pageScrollInProgress &&
        contentReady &&
        pageCount > 1 &&
        currentPage in 0 until pageCount - 1
}

/**
 * 计算一次自动翻页应到达的下一页。
 *
 * 使用方法：
 * 定时倒计时或连续朗读完成后调用。调用方只在返回非空页码时更新阅读器状态，从而保证一次事件
 * 最多前进一页，并在书末自然停止。
 *
 * @param currentPage 当前零基页码，允许传入暂时越界的恢复状态。
 * @param pageCount 当前书籍总页数；小于等于零时视为没有可翻页面。
 * @return 合法的下一页零基页码；当前已经到达末页或参数无效时返回`null`。
 */
internal fun resolveNextEbookAutomaticPage(currentPage: Int, pageCount: Int): Int? {
    if (pageCount <= 0 || currentPage !in 0 until pageCount - 1) return null
    return currentPage + 1
}
