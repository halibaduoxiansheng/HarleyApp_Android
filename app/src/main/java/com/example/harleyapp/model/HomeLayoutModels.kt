package com.example.harleyapp.model

/**
 * 首页搜索栏下方可以由用户整理的内容模块。
 *
 * 使用方法：
 * [HomeLayoutPreferences]使用枚举名称保存顺序和隐藏状态，首页按[sectionOrder]从上到下绘制仍然
 * 可见的模块。新增模块时应在枚举末尾添加，旧版本已经保存的顺序会通过[normalizeHomeSectionOrder]
 * 自动把新模块补到末尾，不会打乱用户原有布局。
 *
 * @param title “整理首页”对话框中显示的模块名称。
 * @param description 模块用途说明，帮助用户在隐藏前确认影响范围。
 */
enum class HomeSectionId(
    val title: String,
    val description: String
) {
    WEBSITES("我的网站", "首页网站轮播、添加和收藏管理入口"),
    FEATURES("功能中心", "用户选择放在首页的常用功能卡片"),
    ENGLISH_WORD("英语单词", "随机复习单词、发音和学习进度"),
    COMPANION("我的伙伴", "伙伴成长、物品和互动入口"),
    REALTIME_STATUS("实时状态", "当前上传与下载速率"),
    SHORTCUT_APPS("快捷应用", "快速打开已选择的手机应用")
}

/** 首页模块首次安装或旧版本升级时沿用的原始排列。 */
val DEFAULT_HOME_SECTION_ORDER: List<HomeSectionId> = HomeSectionId.entries.toList()

/**
 * 首页模块的完整用户布局。
 *
 * 使用方法：
 * 仓库读取本地字符串后构造本对象，首页只遍历[visibleSections]。搜索栏和“整理首页”入口不属于
 * 可隐藏模块，因此即使用户隐藏全部模块，也始终能够恢复布局。
 *
 * @param sectionOrder 当前版本全部模块从上到下的排列顺序。
 * @param hiddenSections 用户明确隐藏的模块集合。
 */
data class HomeLayoutPreferences(
    val sectionOrder: List<HomeSectionId> = DEFAULT_HOME_SECTION_ORDER,
    val hiddenSections: Set<HomeSectionId> = emptySet()
) {
    /** 当前仍需要显示的模块，顺序与[sectionOrder]完全一致。 */
    val visibleSections: List<HomeSectionId>
        get() = sectionOrder.filterNot(hiddenSections::contains)

    /**
     * 判断指定首页模块是否可见。
     *
     * @param section 待检查的首页模块。
     * @return 模块没有被用户隐藏时返回true，否则返回false。
     */
    fun isVisible(section: HomeSectionId): Boolean = section !in hiddenSections
}

/**
 * 把持久化名称恢复为完整、无重复且兼容新版本的首页模块顺序。
 *
 * 使用方法：
 * HomeLayoutRepository读取字符串后调用。已知模块保留保存顺序，重复值和未知值会被忽略；当前
 * 版本新增但旧配置中不存在的模块自动追加到末尾。
 *
 * @param storedNames 按用户排列结果保存的枚举名称。
 * @return 包含当前全部首页模块且没有重复项的稳定顺序。
 */
fun normalizeHomeSectionOrder(storedNames: List<String>): List<HomeSectionId> {
    val orderedSections = linkedSetOf<HomeSectionId>()
    storedNames.forEach { storedName ->
        HomeSectionId.entries.firstOrNull { section ->
            section.name == storedName
        }?.let(orderedSections::add)
    }
    HomeSectionId.entries.forEach(orderedSections::add)

    return orderedSections.toList()
}

/**
 * 把持久化名称恢复为当前版本支持的隐藏模块集合。
 *
 * @param storedNames 本地保存的隐藏模块枚举名称。
 * @return 仅包含当前版本已知模块的集合，未知名称会被安全忽略。
 */
fun decodeHiddenHomeSections(storedNames: Set<String>): Set<HomeSectionId> {
    return HomeSectionId.entries
        .filterTo(linkedSetOf()) { section -> section.name in storedNames }
}

/**
 * 规范化即将保存的首页布局，避免调用方传入缺项、重复项或无效隐藏项。
 *
 * @param layout 页面准备保存的首页布局。
 * @return 顺序完整且隐藏集合只包含当前模块的新布局。
 */
fun normalizeHomeLayoutPreferences(layout: HomeLayoutPreferences): HomeLayoutPreferences {
    val normalizedOrder = normalizeHomeSectionOrder(layout.sectionOrder.map(HomeSectionId::name))
    val normalizedHidden = layout.hiddenSections
        .filterTo(linkedSetOf()) { section -> section in HomeSectionId.entries }
    return HomeLayoutPreferences(
        sectionOrder = normalizedOrder,
        hiddenSections = normalizedHidden
    )
}

/**
 * 把一个首页模块移动到目标索引，同时保持其他模块的相对顺序。
 *
 * 使用方法：
 * “整理首页”对话框点击上移或下移按钮时调用，并把返回列表写回临时布局。保存前不会修改仓库。
 *
 * @param order 当前完整模块顺序。
 * @param fromIndex 待移动模块原索引。
 * @param toIndex 目标索引。
 * @return 移动后的新列表；索引越界或位置未变化时返回原顺序副本。
 */
fun moveHomeSection(
    order: List<HomeSectionId>,
    fromIndex: Int,
    toIndex: Int
): List<HomeSectionId> {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
        return order.toList()
    }

    return order.toMutableList().apply {
        val movedSection = removeAt(fromIndex)
        add(toIndex, movedSection)
    }
}
