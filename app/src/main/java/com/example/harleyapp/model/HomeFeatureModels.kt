package com.example.harleyapp.model

/**
 * 首页“功能中心”允许展示的功能标识。
 *
 * 使用方法：
 * 页面使用枚举值决定需要展示和打开的功能，仓库使用枚举名称完成本地持久化。新增功能时应在
 * 枚举末尾添加新值，并同步补充HomeScreen中的展示说明和导航分支，避免改变旧配置的含义。
 */
enum class HomeFeatureId {
    TODAY,
    SEARCH,
    LEDGER,
    FITNESS,
    HOT_TOPICS,
    MOBILE_DATA,
    WECHAT_REMINDER,
    GENERAL_REMINDER,
    BACKUP,
    LOCAL_CLEANUP,
    ENGLISH_WORDS,
    NOTEBOOK,
    EBOOKS,
    CHINESE_GROWTH,
    QR_SCANNER,
    APP_USAGE,
    BREATH_HOLD,
    FLASHLIGHT,
    MAO_QUOTES,
    DUAL_CAMERA
}

/** 首次安装或旧版本升级时沿用首页原有的三个功能入口。 */
val DEFAULT_HOME_FEATURE_IDS: Set<HomeFeatureId> = linkedSetOf(
    HomeFeatureId.TODAY,
    HomeFeatureId.HOT_TOPICS,
    HomeFeatureId.MOBILE_DATA
)

/**
 * 把本地保存的枚举名称转换为当前版本支持的首页功能集合。
 *
 * 使用方法：
 * HomeFeatureRepository读取字符串集合后调用本函数。旧版本遗留或未来版本写入的未知名称会被
 * 忽略，当前版本仍可正常打开；传入空集合表示用户明确选择不在首页展示任何功能。
 *
 * @param storedNames 本地保存的功能枚举名称集合。
 *
 * @return 当前版本能够识别的功能集合，顺序遵循HomeFeatureId枚举声明顺序。
 */
fun decodeHomeFeatureIds(storedNames: Set<String>): Set<HomeFeatureId> {
    return HomeFeatureId.entries
        .filterTo(linkedSetOf()) { featureId -> featureId.name in storedNames }
}

/** 功能中心首次安装时使用的完整默认排列顺序。 */
val DEFAULT_FEATURE_CENTER_ORDER: List<HomeFeatureId> = HomeFeatureId.entries.toList()

/**
 * 把本地保存的功能名称恢复成完整、无重复且兼容新版本的功能中心顺序。
 *
 * 使用方法：
 * FeatureCenterOrderRepository读取持久化字符串后调用本函数。已保存且仍受支持的功能保持原顺序；
 * 重复项和未知项会被忽略；升级版本后新增的功能会自动追加到末尾，避免入口永久缺失。
 *
 * @param storedNames 按用户拖动结果保存的功能枚举名称列表。
 *
 * @return 包含当前全部功能标识的稳定顺序列表。
 */
fun normalizeFeatureCenterOrder(storedNames: List<String>): List<HomeFeatureId> {
    val orderedFeatures = linkedSetOf<HomeFeatureId>()
    storedNames.forEach { storedName ->
        HomeFeatureId.entries.firstOrNull { featureId ->
            featureId.name == storedName
        }?.let(orderedFeatures::add)
    }
    HomeFeatureId.entries.forEach(orderedFeatures::add)

    return orderedFeatures.toList()
}

/**
 * 把功能中心中的一个入口移动到目标索引，并保持其他入口的相对顺序。
 *
 * @param order 当前功能顺序。
 * @param fromIndex 被拖动入口原索引。
 * @param toIndex 手指当前命中的目标索引。
 *
 * @return 移动后的新列表；索引越界或位置未变化时返回原顺序副本。
 */
fun moveFeatureCenterItem(
    order: List<HomeFeatureId>,
    fromIndex: Int,
    toIndex: Int
): List<HomeFeatureId> {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
        return order.toList()
    }

    return order.toMutableList().apply {
        val movedItem = removeAt(fromIndex)
        add(toIndex, movedItem)
    }
}
