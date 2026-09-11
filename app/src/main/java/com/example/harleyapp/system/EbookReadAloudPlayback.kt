package com.example.harleyapp.system

import com.example.harleyapp.model.EbookTranslationDirection
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 后台电子书朗读会话所处的公开状态。
 *
 * 使用方法：
 * 页面收集[EbookReadAloudPlayback.snapshot]，再根据本枚举更新播放按钮、进度提示和错误信息。
 * [PREPARING]既包含系统TTS初始化，也包含可选的离线译文准备；只有[PLAYING]表示当前确实已经
 * 开始发声。[PAUSED]可以继续播放，[STOPPED]表示服务已主动结束，[COMPLETED]表示已经读到末页。
 *
 * @return 枚举值本身不执行操作，只描述最近一次服务状态快照。
 */
enum class EbookReadAloudPlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
    ERROR
}

/**
 * 同进程后台朗读使用的一页不可变正文。
 *
 * 使用方法：
 * 阅读页面完成屏幕分页后，把每一页原文映射为[EbookReadAloudPage]并按显示顺序放入
 * [EbookReadAloudConfig.pages]。列表下标就是服务、媒体进度和回调统一使用的零基页码。
 *
 * @param text 当前页完整原文；服务不会把该字符串写入Intent，也不会上传到网络。
 */
data class EbookReadAloudPage(
    val text: String
)

/**
 * 一次后台连续朗读所需的完整、同进程配置。
 *
 * 使用方法：
 * 先构造本模型并调用[EbookReadAloudPlayback.register]取得一次性token，再把token交给
 * [EbookReadAloudService.start]。服务会把[initialPage]限制到有效范围，从该页自动开始播放；
 * 页面正文始终只存在进程内注册表，不经过Intent或Bundle，避免大书触发Binder大小限制。
 *
 * @param bookId 书籍稳定ID，用于页面和持久化回调识别当前书。
 * @param title 通知栏、锁屏媒体卡片显示的书名。
 * @param author 通知栏和锁屏媒体卡片显示的作者；未知时传空字符串。
 * @param pages 已完成当前阅读布局分页的有序正文，列表下标为零基页码。
 * @param initialPage 首次启动服务时要朗读的零基页码，越界值会被限制到有效范围。
 * @param naturalReadingEnabled 是否沿用自然分句和稳定音高与速度下的句间停顿。
 * @param translationDirection 可选离线翻译方向；null朗读原文，非null时服务先准备当前页译文再朗读。
 */
data class EbookReadAloudConfig(
    val bookId: String,
    val title: String,
    val author: String,
    val pages: List<EbookReadAloudPage>,
    val initialPage: Int,
    val naturalReadingEnabled: Boolean,
    val translationDirection: EbookTranslationDirection? = null
)

/**
 * 后台朗读对界面公开的单一事实快照。
 *
 * 使用方法：
 * 通过[EbookReadAloudPlayback.snapshot]持续收集。高亮范围和[resumeOffset]都以[spokenText]
 * 的UTF-16索引表示：原文朗读时[spokenText]就是当前页原文，译文朗读时则是当前页译文。
 * [highlightStart]或[highlightEnd]为[NO_HIGHLIGHT]表示当前没有可靠的高亮范围。
 *
 * @param bookId 当前书籍ID；尚未建立会话时为空。
 * @param title 当前书名；尚未建立会话时为空。
 * @param author 当前作者；未知或尚未建立会话时为空。
 * @param status 当前播放状态。
 * @param currentPage 当前零基页码。
 * @param pageCount 当前分页总页数。
 * @param resumeOffset 下一次恢复朗读使用的页内UTF-16起点。
 * @param highlightStart 当前TTS范围的页内UTF-16起点，未知时为[NO_HIGHLIGHT]。
 * @param highlightEnd 当前TTS范围的不包含式页内UTF-16终点，未知时为[NO_HIGHLIGHT]。
 * @param spokenText 当前实际交给TTS的整页原文或译文，便于界面按相同索引显示背景色。
 * @param error 最近一次用户可见错误；正常状态为null。
 */
data class EbookReadAloudSnapshot(
    val bookId: String = "",
    val title: String = "",
    val author: String = "",
    val status: EbookReadAloudPlaybackStatus = EbookReadAloudPlaybackStatus.IDLE,
    val currentPage: Int = 0,
    val pageCount: Int = 0,
    val resumeOffset: Int = 0,
    val highlightStart: Int = NO_HIGHLIGHT,
    val highlightEnd: Int = NO_HIGHLIGHT,
    val spokenText: String = "",
    val error: String? = null
) {

    companion object {
        /** 当前不存在可靠朗读范围时使用的统一哨兵值。 */
        const val NO_HIGHLIGHT = -1
    }
}

/**
 * 把关键播放进度交给宿主持久化层的独立接口。
 *
 * 使用方法：
 * 页面注册配置时可向[EbookReadAloudPlayback.register]传入实现。服务只在切页、暂停和停止三个
 * 低频节点调用，不直接依赖EbookRepository；实现方可保存页码与[resumeOffset]，也可以仅更新UI。
 * 所有回调正常情况下都由服务主线程发出，实现应快速返回，耗时磁盘工作需自行切换后台线程。
 */
interface EbookReadAloudProgressCallback {

    /**
     * 当前页因自动续读、上一页、下一页或跳页发生变化时调用。
     *
     * @param snapshot 已经包含新页码和新页正文的权威快照。
     * @return 无返回值；回调异常会被服务隔离，不会中断朗读。
     */
    fun onPageChanged(snapshot: EbookReadAloudSnapshot)

    /**
     * 播放因用户操作、耳机断开或音频焦点变化进入暂停时调用。
     *
     * @param snapshot 已计算安全词句起点的暂停快照，可直接保存其页码和[resumeOffset]。
     * @return 无返回值；回调异常会被服务隔离，不会中断朗读。
     */
    fun onPlaybackPaused(snapshot: EbookReadAloudSnapshot)

    /**
     * 会话收到STOP或服务被销毁而结束时调用。
     *
     * @param snapshot 最终页码、断点和停止状态快照。
     * @return 无返回值；回调异常会被服务隔离，不会阻止服务释放资源。
     */
    fun onPlaybackStopped(snapshot: EbookReadAloudSnapshot)
}

/**
 * 电子书后台朗读的进程内配置注册表与公开状态入口。
 *
 * 使用方法：
 * 调用[register]登记大体积分页配置，随后只把返回token传入[EbookReadAloudService.start]；界面可
 * 直接收集[snapshot]，无需为了观察状态一直绑定服务。token被服务取走后立即从待启动表删除，
 * 进程被系统终止后也不会自动恢复旧正文或突然发声，符合[START_NOT_STICKY]会话语义。
 *
 * @return 本对象不需要实例化，公开成员提供注册、撤销和状态观察能力。
 */
object EbookReadAloudPlayback {

    /** 服务消费的一次性配置与可选进度回调。 */
    internal data class RegisteredSession(
        val config: EbookReadAloudConfig,
        val progressCallback: EbookReadAloudProgressCallback?
    )

    private val registryLock = Any()
    private val pendingSessions = LinkedHashMap<String, RegisteredSession>()
    private val mutableSnapshot = MutableStateFlow(EbookReadAloudSnapshot())

    /**
     * 服务当前状态的只读热流。
     *
     * 使用方法：
     * Compose或其他生命周期宿主按StateFlow常规方式收集；新订阅者会立即得到最近快照。
     *
     * @return 进程内唯一的电子书后台朗读状态流。
     */
    val snapshot: StateFlow<EbookReadAloudSnapshot> = mutableSnapshot.asStateFlow()

    /**
     * 登记一次后台朗读配置并生成只用于服务启动的短token。
     *
     * 使用方法：
     * 页面确认[config]分页完成且至少有一页后调用；成功取得token后应尽快调用
     * [EbookReadAloudService.start]。若启动请求失败，应调用[unregister]释放尚未消费的配置。
     * 为避免意外修改，函数会浅复制页列表；正文字符串本身不可变，不会重复复制大文本字节。
     *
     * @param config 包含书籍元数据、分页正文和朗读选项的完整同进程配置。
     * @param progressCallback 可选低频进度发布接口，服务不直接访问任何Repository。
     * @return 不透明一次性token；配置非法时抛出IllegalArgumentException提醒调用方修正接入。
     */
    fun register(
        config: EbookReadAloudConfig,
        progressCallback: EbookReadAloudProgressCallback? = null
    ): String {
        require(config.bookId.isNotBlank()) { "Ebook read-aloud book id must not be blank" }
        require(config.title.isNotBlank()) { "Ebook read-aloud title must not be blank" }
        require(config.pages.isNotEmpty()) { "Ebook read-aloud pages must not be empty" }

        val safeConfig = config.copy(
            pages = config.pages.toList(),
            initialPage = config.initialPage.coerceIn(config.pages.indices)
        )
        val token = UUID.randomUUID().toString()
        synchronized(registryLock) {
            pendingSessions[token] = RegisteredSession(safeConfig, progressCallback)
            trimPendingSessionsLocked()
        }
        return token
    }

    /**
     * 撤销尚未被服务消费的启动配置。
     *
     * 使用方法：
     * [EbookReadAloudService.start]返回false或页面在启动前取消时调用；已经启动的会话应通过服务
     * Binder或媒体STOP结束，本函数不会中断正在播放的服务。
     *
     * @param token [register]返回的一次性token。
     * @return 找到并删除待启动配置返回true；token无效或已被服务消费返回false。
     */
    fun unregister(token: String): Boolean {
        if (token.isBlank()) return false
        return synchronized(registryLock) {
            pendingSessions.remove(token) != null
        }
    }

    /**
     * 由服务按token一次性取得完整配置。
     *
     * @param token 启动Intent携带的唯一轻量字段。
     * @return 对应配置与回调；token为空、过期、重复消费或进程已重建时返回null。
     */
    internal fun consume(token: String?): RegisteredSession? {
        if (token.isNullOrBlank()) return null
        return synchronized(registryLock) {
            pendingSessions.remove(token)
        }
    }

    /**
     * 由服务发布新的权威快照。
     *
     * @param value 已完成边界限制的会话状态。
     * @return 无返回值；所有当前及后续订阅者都会看到该值。
     */
    internal fun publish(value: EbookReadAloudSnapshot) {
        mutableSnapshot.value = value
    }

    /**
     * 限制尚未启动的配置数量，避免页面连续注册却不启动时长期持有多本书正文。
     *
     * 使用方法：
     * 仅在持有[registryLock]时调用；按插入顺序丢弃最早且从未消费的配置。
     *
     * @return 无返回值；注册表最终不会超过[MAX_PENDING_SESSIONS]。
     */
    private fun trimPendingSessionsLocked() {
        while (pendingSessions.size > MAX_PENDING_SESSIONS) {
            val oldestToken = pendingSessions.entries.firstOrNull()?.key ?: return
            pendingSessions.remove(oldestToken)
        }
    }

    private const val MAX_PENDING_SESSIONS = 4
}

/**
 * 根据最新TTS范围起点计算“宁可重复、不跳字”的恢复位置。
 *
 * 使用方法：
 * 服务暂停时传入当前[spokenText]和最近一次range start。英文、数字等以空白或标点为词边界；
 * 连续中日韩文字没有可靠系统分词结果时回退到最近句末后的句首，因此恢复时可能重复当前句，
 * 但不会从一个UTF-16代理项中间开始或跳过尚未发声的文字。
 *
 * @param text 当前实际朗读的整页原文或译文。
 * @param reportedRangeStart Android TTS最近回报的页内UTF-16起点。
 * @return 限制在0到正文长度之间且不拆开代理项的安全词首或句首位置。
 */
internal fun findEbookReadAloudResumeOffset(
    text: String,
    reportedRangeStart: Int
): Int {
    if (text.isEmpty()) return 0

    var safeStart = reportedRangeStart.coerceIn(0, text.length)
    if (
        safeStart in 1 until text.length &&
        Character.isHighSurrogate(text[safeStart - 1]) &&
        Character.isLowSurrogate(text[safeStart])
    ) {
        safeStart -= 1
    }
    if (safeStart == 0 || safeStart == text.length) return safeStart

    val currentCharacter = text[safeStart]
    val useSentenceBoundary = isCjkCharacter(currentCharacter)
    var candidate = safeStart
    while (candidate > 0) {
        val previous = text[candidate - 1]
        if (useSentenceBoundary) {
            if (previous in EBOOK_RESUME_SENTENCE_BOUNDARIES) break
        } else if (previous.isWhitespace() || previous in EBOOK_RESUME_WORD_BOUNDARIES) {
            break
        }
        candidate -= 1
    }
    while (candidate < safeStart && text[candidate].isWhitespace()) {
        candidate += 1
    }
    return candidate
}

/**
 * 判断字符是否属于常见中日韩统一表意文字范围。
 *
 * @param character 待判断的一个UTF-16字符；扩展区代理对不会被单独视为基础CJK字符。
 * @return 基础或扩展A区中日韩文字返回true，其余字符返回false。
 */
private fun isCjkCharacter(character: Char): Boolean {
    return character.code in CJK_UNIFIED_RANGE || character.code in CJK_EXTENSION_A_RANGE
}

private val CJK_UNIFIED_RANGE = 0x4E00..0x9FFF
private val CJK_EXTENSION_A_RANGE = 0x3400..0x4DBF
private val EBOOK_RESUME_SENTENCE_BOUNDARIES = setOf(
    '。', '！', '？', '；', '.', '!', '?', ';', '\n', '\r'
)
private val EBOOK_RESUME_WORD_BOUNDARIES = setOf(
    ',', '，', '。', '.', '!', '！', '?', '？', ';', '；', ':', '：',
    '(', ')', '（', '）', '[', ']', '【', '】', '"', '\'', '“', '”', '‘', '’'
)
