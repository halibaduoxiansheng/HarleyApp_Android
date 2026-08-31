package com.example.harleyapp.model

/**
 * App可选的整体视觉主题。
 *
 * 使用方法：
 * AppearanceRepository只保存枚举名称，MainActivity读取后传给HarleyAppTheme生成全局配色；
 * “我的”页面使用名称、说明、符号、预览色和离线人物图展示选择器。可选人物素材必须同时保存
 * 明确来源和许可，避免依赖网络或使用版权状态不明的知名角色。
 *
 * @param displayName 用户看到的主题名称。
 * @param description 主题气质和主要颜色说明。
 * @param symbol 主题选择器使用的简洁原创装饰符号。
 * @param previewColorArgb 主题主色ARGB，用于选择器预览。
 * @param artResourceName drawable-nodpi中的离线人物资源名，不含扩展名。
 * @param artCredit 页面显示的简短素材来源；完整许可保存在assets/theme_art_notice.txt。
 */
enum class AppVisualTheme(
    val displayName: String,
    val description: String,
    val symbol: String,
    val previewColorArgb: Long,
    val artResourceName: String,
    val artCredit: String
) {
    CLASSIC_BLUE(
        displayName = "经典蓝",
        description = "清爽稳重的纯色界面，不使用任何角色插画",
        symbol = "蓝",
        previewColorArgb = 0xFF3856C8L,
        artResourceName = "",
        artCredit = "纯色主题 · 无角色插画"
    ),
    SAKURA_GIRL(
        displayName = "樱花少女",
        description = "樱粉与葡萄紫，轻柔的二次元春日氛围",
        symbol = "樱",
        previewColorArgb = 0xFFC33C83L,
        artResourceName = "theme_sakura_girl",
        artCredit = "Kitsuge Apps · CC0"
    ),
    STARRY_TRAVELER(
        displayName = "星海旅人",
        description = "深靛与星光青，适合偏冷色的幻想风格",
        symbol = "星",
        previewColorArgb = 0xFF4D5BD4L,
        artResourceName = "theme_starry_traveler",
        artCredit = "Kitsuge Apps · CC0"
    ),
    MINT_CAT(
        displayName = "薄荷双马尾",
        description = "薄荷绿与暖橙，带人物插画的治愈风格",
        symbol = "喵",
        previewColorArgb = 0xFF007C73L,
        artResourceName = "theme_mint_cat",
        artCredit = "Kitsuge Apps · CC0"
    ),
    AMBER_SHORT_HAIR(
        displayName = "琥珀短发",
        description = "暖橙背景与沉静短发女孩，简洁而有力量",
        symbol = "琥",
        previewColorArgb = 0xFFE6843DL,
        artResourceName = "theme_peach_artist",
        artCredit = "Kitsuge Apps · CC0"
    ),
    CORAL_HOODIE(
        displayName = "珊瑚卫衣",
        description = "亮蓝与珊瑚红，俏皮街头角色风格",
        symbol = "珊",
        previewColorArgb = 0xFF158DBFL,
        artResourceName = "theme_violet_student",
        artCredit = "Kitsuge Apps · CC0"
    ),
    PEACH_TWIN_TAIL(
        displayName = "蜜桃双马尾",
        description = "粉白条纹与眼镜双马尾女孩，轻柔学院风",
        symbol = "桃",
        previewColorArgb = 0xFFD76785L,
        artResourceName = "theme_aqua_dreamer",
        artCredit = "Kitsuge Apps · CC0"
    ),
    CYAN_SHORT_HAIR(
        displayName = "青空短发",
        description = "青色短发与淡紫波纹，清爽未来感",
        symbol = "青",
        previewColorArgb = 0xFF159DA8L,
        artResourceName = "theme_orange_ribbon",
        artCredit = "Kitsuge Apps · CC0"
    ),
    NIGHT_MUSIC(
        displayName = "夜色音乐",
        description = "深色背景与耳机角色，适合沉浸式夜间使用",
        symbol = "夜",
        previewColorArgb = 0xFF274A70L,
        artResourceName = "theme_lilac_friend",
        artCredit = "Kitsuge Apps · CC0"
    ),
    SKY_ADVENTURER(
        displayName = "晴空冒险",
        description = "云朵、金发与明亮蓝眼，轻快行动派风格",
        symbol = "晴",
        previewColorArgb = 0xFF4999C9L,
        artResourceName = "theme_sky_idol",
        artCredit = "Kitsuge Apps · CC0"
    ),
    ROSE_PRINCESS(
        displayName = "玫瑰公主",
        description = "玫红长发女孩与紫色裙装，明艳梦幻风",
        symbol = "玫",
        previewColorArgb = 0xFFE62B85L,
        artResourceName = "theme_rose_reader",
        artCredit = "Kitsuge Apps · CC0"
    );

    companion object {

        /**
         * 把本地保存的枚举名称恢复为仍受当前版本支持的主题。
         *
         * @param storedName SharedPreferences读取的枚举名称；旧版本没有该值时可为null。
         *
         * @return 匹配的主题；名称缺失或未来版本值无法识别时回退到经典蓝。
         */
        fun fromStoredName(storedName: String?): AppVisualTheme {
            return entries.firstOrNull { theme -> theme.name == storedName }
                ?: CLASSIC_BLUE
        }
    }
}
