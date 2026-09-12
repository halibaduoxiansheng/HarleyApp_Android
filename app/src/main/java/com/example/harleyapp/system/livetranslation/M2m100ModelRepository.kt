package com.example.harleyapp.system.livetranslation

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * M2M100本地翻译模型中一个不可变文件的下载与完整性契约。
 *
 * 使用方法：
 * 只能使用[M2m100ModelRepository.PINNED_FILES]中随代码固定的实例。下载器会同时核对文件名、固定提交
 * URL、精确字节数和SHA-256，禁止把分支最新版本或未经校验的响应直接安装为可运行模型。
 *
 * @param fileName 安装到应用私有翻译模型目录中的固定文件名。
 * @param url 指向Xenova转换仓库不可变提交的HTTPS下载地址。
 * @param sizeBytes 文件精确字节数。
 * @param sha256 文件小写十六进制SHA-256。
 */
internal data class PinnedM2m100ModelFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * 下载、断点续传、校验并原子安装M2M100-418M INT8翻译模型。
 *
 * 使用方法：
 * [LiveTranslationModelManager]在用户主动点击“准备模型”后调用[prepare]，页面刷新和服务启动前分别调用
 * [isInstalled]与[requireInstalledDirectory]。运行时推理只读取[modelDirectory]，不会执行网络请求；所有下载
 * 均发生在明确的模型准备阶段。
 *
 * 模型权重来自Xenova对Meta M2M100-418M的第三方ONNX转换，固定到提交
 * `cce333fc730dab2125fbc054a18fa2565db31095`。该来源不是Meta官方ONNX发布，因此安装标记会同时固定
 * 上游模型身份、转换提交以及每个文件的摘要，避免把来源说明与实际字节混淆。
 *
 * @param modelRoot 应用私有的实时翻译模型根目录。
 */
internal class M2m100ModelRepository(
    private val modelRoot: File
) {

    /** 已验证模型的正式安装目录；服务只能在[isInstalled]为true时使用。 */
    val modelDirectory = File(modelRoot, MODEL_DIRECTORY_NAME)

    private val installingDirectory = File(modelRoot, "$MODEL_DIRECTORY_NAME.installing")

    /**
     * 快速检查正式目录是否满足已校验安装契约。
     *
     * 使用方法：
     * 页面刷新和服务启动前均可同步调用。函数只比较私有目录内的固定路径、精确长度与安装标记，不会联网，
     * 也不会在每次启动时重复读取约1.21 GB权重计算哈希；完整哈希已经在文件进入正式目录前逐一计算。
     *
     * @return 所有固定文件和完整安装标记都存在且长度匹配时返回true，否则返回false。
     */
    fun isInstalled(): Boolean {
        if (!modelDirectory.isDirectory) return false

        val allFilesPresent = PINNED_FILES.all { descriptor ->
            val file = File(modelDirectory, descriptor.fileName)
            file.isFile && file.length() == descriptor.sizeBytes
        }
        if (!allFilesPresent) return false

        val marker = File(modelDirectory, INSTALL_MARKER_FILE_NAME)
        return marker.isFile &&
            runCatching { marker.readText(Charsets.UTF_8) == INSTALL_MARKER_CONTENT }
                .getOrDefault(false)
    }

    /**
     * 返回已经完整校验并安装的翻译模型目录。
     *
     * 使用方法：
     * 前台服务必须在创建[OnDeviceLiveTranslator]及开始AudioRecord之前调用；返回目录可直接传给翻译器
     * 构造函数。该函数不会下载或修复文件，缺失时应让用户回到模型准备页面主动重试。
     *
     * @return 包含三个量化ONNX文件、SentencePiece模型和官方词表的应用私有目录。
     * @throws LiveTranslationModelAssetsMissingException 文件或安装标记不满足固定契约时抛出。
     */
    fun requireInstalledDirectory(): File {
        if (!isInstalled()) throw LiveTranslationModelAssetsMissingException()
        return modelDirectory
    }

    /**
     * 顺序准备五个固定模型文件，并在全部SHA-256校验成功后原子发布正式目录。
     *
     * 使用方法：
     * 只能由模型管理器的互斥后台任务调用。取消协程时保留`.part`和已经校验的暂存文件，下次从安全边界
     * 继续；暂存目录没有完整标记，永远不会被服务误认为可用模型。
     *
     * @param onProgress 每写入新字节时回调总体百分比与中文阶段说明，供页面更新，不包含URL或文件路径。
     * @return 无返回值；下载、长度、哈希或原子安装任一步失败时抛出异常。
     */
    suspend fun prepare(onProgress: (progressPercent: Int, message: String) -> Unit) {
        if (isInstalled()) return

        if (canReuseInstalledCoreFiles()) {
            prepareVocabularyOnlyUpgrade(onProgress)
            return
        }

        check(modelRoot.mkdirs() || modelRoot.isDirectory) {
            "Unable to create the live translation model root"
        }
        check(installingDirectory.mkdirs() || installingDirectory.isDirectory) {
            "Unable to create the M2M100 staging directory"
        }

        var completedBytes = 0L
        for (descriptor in PINNED_FILES) {
            currentCoroutineContext().ensureActive()
            val destination = File(installingDirectory, descriptor.fileName)
            if (isFileValid(destination, descriptor)) {
                completedBytes += descriptor.sizeBytes
                publishProgress(completedBytes, descriptor.fileName, onProgress)
                continue
            }
            if (destination.exists()) {
                check(destination.delete()) { "Unable to discard an invalid staged model file" }
            }

            downloadAndVerifyFile(
                descriptor = descriptor,
                targetDirectory = installingDirectory,
                completedBytesBeforeFile = completedBytes,
                onProgress = onProgress
            )
            completedBytes += descriptor.sizeBytes
        }

        File(installingDirectory, INSTALL_MARKER_FILE_NAME).writeText(
            text = INSTALL_MARKER_CONTENT,
            charset = Charsets.UTF_8
        )

        if (modelDirectory.exists()) {
            check(modelDirectory.deleteRecursively()) {
                "Unable to replace the previous M2M100 model directory"
            }
        }
        check(installingDirectory.renameTo(modelDirectory)) {
            "Unable to finalize the M2M100 model directory"
        }
    }

    /**
     * 判断现有正式目录能否安全复用三个ONNX权重和SentencePiece模型，只增量补充`vocab.json`。
     *
     * 使用方法：
     * [LiveTranslationModelManager]在下载前据此选择空间门槛，[prepare]也会再次检查后决定是否走原地升级。
     * 只有四个旧文件长度完整，并且标记精确匹配旧schema或当前schema时才返回true；不会仅凭文件名信任
     * 来源不明的大文件。
     *
     * @return 旧核心文件及可信安装标记均满足复用契约时返回true，否则返回false。
     */
    fun canReuseInstalledCoreFiles(): Boolean {
        if (!modelDirectory.isDirectory) return false

        val coreFilesPresent = LEGACY_PINNED_FILES.all { descriptor ->
            val file = File(modelDirectory, descriptor.fileName)
            file.isFile && file.length() == descriptor.sizeBytes
        }
        if (!coreFilesPresent) return false

        val markerText = runCatching {
            File(modelDirectory, INSTALL_MARKER_FILE_NAME).readText(Charsets.UTF_8)
        }.getOrNull()
        return markerText == LEGACY_INSTALL_MARKER_CONTENT || markerText == INSTALL_MARKER_CONTENT
    }

    /**
     * 在旧正式目录中仅补充官方`vocab.json`，成功后原子升级安装标记。
     *
     * 使用方法：
     * 仅在[canReuseInstalledCoreFiles]返回true后调用。下载片段和最终词表都写在同一正式目录，取消或失败
     * 只会留下可续传的词表片段，既有约1.2 GB权重及旧标记均保持不变；词表完整校验通过后才替换标记。
     *
     * @param onProgress 总进度回调。
     * @return 无返回值；词表下载、校验或原子标记更新失败时抛出异常。
     */
    private suspend fun prepareVocabularyOnlyUpgrade(
        onProgress: (progressPercent: Int, message: String) -> Unit
    ) {
        check(canReuseInstalledCoreFiles()) {
            "Existing M2M100 core files are not eligible for incremental upgrade"
        }

        val descriptor = VOCABULARY_DESCRIPTOR
        val destination = File(modelDirectory, descriptor.fileName)
        if (!isFileValid(destination, descriptor)) {
            if (destination.exists()) {
                check(destination.delete()) { "Unable to discard an invalid M2M100 vocabulary" }
            }
            downloadAndVerifyFile(
                descriptor = descriptor,
                targetDirectory = modelDirectory,
                completedBytesBeforeFile = LEGACY_TOTAL_DOWNLOAD_SIZE_BYTES,
                onProgress = onProgress
            )
        } else {
            publishProgress(TOTAL_DOWNLOAD_SIZE_BYTES, descriptor.fileName, onProgress)
        }

        writeInstallMarkerAtomically()
        check(isInstalled()) { "Incremental M2M100 vocabulary upgrade did not finalize correctly" }
    }

    /**
     * 先完整写入同目录临时标记，再以文件系统原子移动替换旧标记。
     *
     * @return 无返回值；底层文件系统不支持原子替换或写入失败时抛出异常，旧标记保持不变。
     */
    private fun writeInstallMarkerAtomically() {
        val marker = File(modelDirectory, INSTALL_MARKER_FILE_NAME)
        val temporaryMarker = File(modelDirectory, "$INSTALL_MARKER_FILE_NAME.next")
        FileOutputStream(temporaryMarker, false).use { output ->
            output.write(INSTALL_MARKER_CONTENT.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporaryMarker.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Throwable) {
            temporaryMarker.delete()
            throw error
        }
    }

    /**
     * 下载单个固定文件；服务器支持Range时从现有片段继续，否则从零安全覆盖。
     *
     * @param descriptor 当前文件的固定来源与摘要契约。
     * @param completedBytesBeforeFile 之前文件已经完成的总字节数，用于计算总进度。
     * @param onProgress 总进度回调。
     * @return 无返回值；最终文件只有通过精确长度及SHA-256后才从`.part`改名。
     */
    private suspend fun downloadAndVerifyFile(
        descriptor: PinnedM2m100ModelFile,
        targetDirectory: File,
        completedBytesBeforeFile: Long,
        onProgress: (progressPercent: Int, message: String) -> Unit
    ) {
        val destination = File(targetDirectory, descriptor.fileName)
        val partial = File(targetDirectory, "${descriptor.fileName}.part")

        if (partial.length() == descriptor.sizeBytes) {
            if (calculateSha256(partial) == descriptor.sha256) {
                finalizePartialFile(partial, destination)
                publishProgress(
                    completedBytesBeforeFile + descriptor.sizeBytes,
                    descriptor.fileName,
                    onProgress
                )
                return
            }
            check(partial.delete()) { "Unable to discard an invalid completed model part" }
        } else if (partial.length() > descriptor.sizeBytes) {
            check(partial.delete()) { "Unable to discard an oversized model part" }
        }

        val existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
        val connection = (URL(descriptor.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_CONNECT_TIMEOUT_MILLIS
            readTimeout = NETWORK_READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("M2M100 model download returned HTTP $responseCode")
            }

            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                check(
                    isExpectedContentRange(
                        headerValue = connection.getHeaderField("Content-Range"),
                        expectedStart = existingBytes,
                        expectedTotal = descriptor.sizeBytes
                    )
                ) { "M2M100 model download returned an invalid Content-Range" }
            }
            val startingBytes = if (append) existingBytes else 0L

            BufferedInputStream(connection.inputStream, DOWNLOAD_BUFFER_SIZE_BYTES).use { input ->
                FileOutputStream(partial, append).use { fileOutput ->
                    BufferedOutputStream(fileOutput, DOWNLOAD_BUFFER_SIZE_BYTES).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                        var downloadedBytes = startingBytes
                        var lastProgress = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val readCount = input.read(buffer)
                            if (readCount < 0) break

                            output.write(buffer, 0, readCount)
                            downloadedBytes += readCount
                            check(downloadedBytes <= descriptor.sizeBytes) {
                                "M2M100 model download exceeded the fixed file length"
                            }

                            val totalCompleted = completedBytesBeforeFile + downloadedBytes
                            val progress = calculateOverallProgress(totalCompleted)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(
                                    progress,
                                    "正在下载高质量本地翻译模型（$progress%，共约1.21 GB）"
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        check(partial.length() == descriptor.sizeBytes) {
            "M2M100 model file length check failed"
        }
        check(calculateSha256(partial) == descriptor.sha256) {
            "M2M100 model file SHA-256 check failed"
        }
        finalizePartialFile(partial, destination)
    }

    /**
     * 把已经通过完整校验的片段发布为暂存目录中的正式文件。
     *
     * @param partial 已验证的`.part`文件。
     * @param destination 同一暂存目录中的固定目标文件。
     * @return 无返回值；无法替换旧目标或改名时抛出异常。
     */
    private fun finalizePartialFile(partial: File, destination: File) {
        if (destination.exists()) {
            check(destination.delete()) { "Unable to replace a staged M2M100 model file" }
        }
        check(partial.renameTo(destination)) { "Unable to finalize an M2M100 model file" }
    }

    /**
     * 对暂存文件执行精确长度和SHA-256检查。
     *
     * @param file 可能来自上次取消任务的已完成暂存文件。
     * @param descriptor 该文件必须满足的固定契约。
     * @return 长度及摘要均一致时返回true，否则返回false。
     */
    private suspend fun isFileValid(
        file: File,
        descriptor: PinnedM2m100ModelFile
    ): Boolean {
        return file.isFile &&
            file.length() == descriptor.sizeBytes &&
            calculateSha256(file) == descriptor.sha256
    }

    /**
     * 流式计算大文件SHA-256，并在每个缓冲区边界响应准备任务取消。
     *
     * @param file 待校验的模型文件。
     * @return 小写十六进制SHA-256。
     */
    private suspend fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream(), DOWNLOAD_BUFFER_SIZE_BYTES).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val readCount = input.read(buffer)
                if (readCount < 0) break
                digest.update(buffer, 0, readCount)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * 发布已完成文件边界上的总体进度。
     *
     * @param completedBytes 已完整校验的累计字节数。
     * @param fileName 仅用于内部阶段说明，不包含设备路径。
     * @param onProgress 页面进度回调。
     * @return 无返回值。
     */
    private fun publishProgress(
        completedBytes: Long,
        fileName: String,
        onProgress: (progressPercent: Int, message: String) -> Unit
    ) {
        val progress = calculateOverallProgress(completedBytes)
        onProgress(progress, "已校验$fileName，正在准备高质量本地翻译模型（$progress%）")
    }

    companion object {
        const val MODEL_DIRECTORY_NAME = "m2m100-418m-int8-cce333fc"
        const val ENCODER_FILE_NAME = "encoder_model_quantized.onnx"
        const val DECODER_FILE_NAME = "decoder_model_quantized.onnx"
        const val DECODER_WITH_PAST_FILE_NAME = "decoder_with_past_model_quantized.onnx"
        const val SENTENCEPIECE_FILE_NAME = "sentencepiece.bpe.model"
        const val VOCABULARY_FILE_NAME = "vocab.json"
        const val TOTAL_DOWNLOAD_SIZE_BYTES = 1_210_487_907L

        private const val INSTALL_MARKER_FILE_NAME = ".verified"
        private const val INSTALL_MARKER_SCHEMA_VERSION = 2
        private const val LEGACY_INSTALL_MARKER_SCHEMA_VERSION = 1
        private const val FIXED_REVISION = "cce333fc730dab2125fbc054a18fa2565db31095"
        private const val DOWNLOAD_BASE_URL =
            "https://huggingface.co/Xenova/m2m100_418M/resolve/$FIXED_REVISION"
        private const val NETWORK_CONNECT_TIMEOUT_MILLIS = 20_000
        private const val NETWORK_READ_TIMEOUT_MILLIS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE_BYTES = 256 * 1024

        /** 旧schema已经完整验证的四个核心文件；只用于识别可安全增量升级的正式目录。 */
        private val LEGACY_PINNED_FILES = listOf(
            PinnedM2m100ModelFile(
                fileName = ENCODER_FILE_NAME,
                url = "$DOWNLOAD_BASE_URL/onnx/$ENCODER_FILE_NAME",
                sizeBytes = 287_856_370L,
                sha256 = "dabfae22dc44c7c5cfbdcd2d85fd7daebf7dc415255645a3454c8554e8fdd137"
            ),
            PinnedM2m100ModelFile(
                fileName = DECODER_FILE_NAME,
                url = "$DOWNLOAD_BASE_URL/onnx/$DECODER_FILE_NAME",
                sizeBytes = 471_009_755L,
                sha256 = "c21aad0d0fe5553c79190831a1be55427806f67d96a046019157ffd89f9ab77c"
            ),
            PinnedM2m100ModelFile(
                fileName = DECODER_WITH_PAST_FILE_NAME,
                url = "$DOWNLOAD_BASE_URL/onnx/$DECODER_WITH_PAST_FILE_NAME",
                sizeBytes = 445_490_297L,
                sha256 = "d450287dbd54564a1d3260f4dd583619dd2b60943838d8b1d59e27f81bc5b26a"
            ),
            PinnedM2m100ModelFile(
                fileName = SENTENCEPIECE_FILE_NAME,
                url = "$DOWNLOAD_BASE_URL/$SENTENCEPIECE_FILE_NAME",
                sizeBytes = 2_423_393L,
                sha256 = "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a"
            )
        )

        /** 官方M2M100模型词表；正文piece必须通过此文件映射到模型ID，不能使用SentencePiece原始序号。 */
        private val VOCABULARY_DESCRIPTOR = PinnedM2m100ModelFile(
            fileName = VOCABULARY_FILE_NAME,
            url = "$DOWNLOAD_BASE_URL/$VOCABULARY_FILE_NAME",
            sizeBytes = 3_708_092L,
            sha256 = "b6e77e474aeea8f441363aca7614317c06381f3eacfe10fb9856d5081d1074cc"
        )

        /** 五个运行必需文件的固定来源、字节数和SHA-256；顺序同时决定全新安装的下载顺序。 */
        val PINNED_FILES = LEGACY_PINNED_FILES + VOCABULARY_DESCRIPTOR

        private const val LEGACY_TOTAL_DOWNLOAD_SIZE_BYTES = 1_206_779_815L

        private val LEGACY_INSTALL_MARKER_CONTENT = buildM2m100InstallMarker(
            schemaVersion = LEGACY_INSTALL_MARKER_SCHEMA_VERSION,
            revision = FIXED_REVISION,
            files = LEGACY_PINNED_FILES
        )

        private val INSTALL_MARKER_CONTENT = buildM2m100InstallMarker(
            schemaVersion = INSTALL_MARKER_SCHEMA_VERSION,
            revision = FIXED_REVISION,
            files = PINNED_FILES
        )

        /**
         * 按总固定字节数计算页面百分比，模型未原子安装完成前最高只显示99%。
         *
         * @param completedBytes 当前已写入或复用的累计字节数。
         * @return 0到99之间的整数百分比。
         */
        internal fun calculateOverallProgress(completedBytes: Long): Int {
            return ((completedBytes.coerceIn(0L, TOTAL_DOWNLOAD_SIZE_BYTES) * 100L) /
                TOTAL_DOWNLOAD_SIZE_BYTES)
                .toInt()
                .coerceIn(0, 99)
        }
    }
}

/**
 * 生成绑定转换提交及每个模型文件完整契约的安装标记。
 *
 * 使用方法：
 * 下载器在所有文件逐一哈希通过后写入返回值；启动检查使用同一组固定描述重新生成并完整比较。
 *
 * @param schemaVersion 标记格式版本，字段结构变化时递增。
 * @param revision Xenova ONNX转换仓库的不可变提交SHA。
 * @param files 按固定顺序排列的模型文件契约。
 * @return 字段顺序稳定、无末尾换行的ASCII安装标记。
 */
internal fun buildM2m100InstallMarker(
    schemaVersion: Int,
    revision: String,
    files: List<PinnedM2m100ModelFile>
): String {
    require(schemaVersion > 0) { "M2M100 install marker schema version must be positive" }
    require(revision.matches(Regex("[0-9a-f]{40}"))) { "M2M100 revision must be a full commit SHA" }
    require(files.isNotEmpty()) { "M2M100 install marker requires model files" }

    return buildList {
        add("schema_version=$schemaVersion")
        add("conversion_revision=$revision")
        files.forEach { descriptor ->
            add("${descriptor.fileName}.size=${descriptor.sizeBytes}")
            add("${descriptor.fileName}.sha256=${descriptor.sha256}")
        }
    }.joinToString(separator = "\n")
}

/**
 * 检查HTTP 206响应是否从期望偏移续传同一个固定长度文件。
 *
 * 使用方法：
 * 只有函数返回true时才允许以追加模式打开`.part`；HTTP 200响应由调用方从零覆盖。结束位置允许小于
 * 文件末尾，以兼容代理分段响应，但总长度必须与固定描述一致。
 *
 * @param headerValue HTTP `Content-Range`原始值。
 * @param expectedStart 本地片段当前精确长度。
 * @param expectedTotal 固定文件总长度。
 * @return 起始偏移、结束位置和总长度均合法时返回true，否则返回false。
 */
internal fun isExpectedContentRange(
    headerValue: String?,
    expectedStart: Long,
    expectedTotal: Long
): Boolean {
    if (headerValue == null || expectedStart < 0L || expectedTotal <= expectedStart) return false
    val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        .matchEntire(headerValue.trim()) ?: return false
    val (startText, endText, totalText) = match.destructured
    val start = startText.toLongOrNull() ?: return false
    val end = endText.toLongOrNull() ?: return false
    val total = totalText.toLongOrNull() ?: return false
    return start == expectedStart && end in start until expectedTotal && total == expectedTotal
}

/** M2M100五个固定运行文件或安装标记缺失、损坏，不能启动本地翻译会话。 */
internal class LiveTranslationModelAssetsMissingException : IllegalStateException(
    "Required local M2M100 translation assets are missing or invalid"
)
