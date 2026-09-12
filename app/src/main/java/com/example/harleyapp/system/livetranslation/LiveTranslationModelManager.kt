package com.example.harleyapp.system.livetranslation

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.harleyapp.model.LiveTranslationModelSnapshot
import com.example.harleyapp.model.LiveTranslationModelStage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 负责下载、校验、安装实时翻译所需本地模型的进程级管理器。
 *
 * 使用方法：
 * 页面控制器通过[get]取得单例并观察[snapshot]；只有用户主动点击后才调用[prepareModels]。
 * 下载支持保留部分文件并在下次继续，安装完成后会准备英语、日语到中文的M2M100设备端模型。
 * 运行服务应通过[requireReadyModelDirectories]核验ASR与翻译权重，再创建本地识别器和翻译器。
 *
 * @param context 应用Context，用于访问应用私有模型目录。
 */
class LiveTranslationModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelRoot = File(appContext.filesDir, MODEL_ROOT_DIRECTORY_NAME)
    private val translationModelRepository = M2m100ModelRepository(modelRoot)
    private val modelDirectory = File(modelRoot, MODEL_DIRECTORY_NAME)
    private val archiveFile = File(modelRoot, MODEL_ARCHIVE_FILE_NAME)
    private val partialArchiveFile = File(modelRoot, "$MODEL_ARCHIVE_FILE_NAME.part")
    private val installingDirectory = File(modelRoot, "$MODEL_DIRECTORY_NAME.installing")
    private val taskLock = Any()

    @Volatile
    private var translationModelsVerifiedReady: Boolean? = null

    private val mutableSnapshot = MutableStateFlow(readInstalledSnapshot())
    private var prepareJob: Job? = null
    private var verificationJob: Job? = null
    private var pendingPreparationTerminalSnapshot: LiveTranslationModelSnapshot? = null

    /** 当前模型准备阶段、进度和面向用户说明。 */
    val snapshot: StateFlow<LiveTranslationModelSnapshot> = mutableSnapshot.asStateFlow()

    init {
        refresh()
    }

    /**
     * 根据私有目录和完成标记重新发布真实模型状态。
     *
     * 使用方法：
     * 页面从后台返回或创建新控制器时调用。正在准备期间不会覆盖下载进度；该函数同步检查固定路径、
     * 大小和完成标记，不会读取音频、联网或重复计算大模型哈希。ASR已安装时会在后台快速核验
     * M2M100固定文件与安装标记。
     *
     * @return 无返回值，最新状态通过[snapshot]发布。
     */
    fun refresh() {
        val shouldVerifyTranslationModels = synchronized(taskLock) {
            if (prepareJob != null) return

            val asrReady = isAsrModelInstalled()
            if (!asrReady) {
                translationModelsVerifiedReady = null
                verificationJob?.cancel()
            }
            mutableSnapshot.value = readInstalledSnapshot(asrReady)
            asrReady
        }

        if (shouldVerifyTranslationModels) {
            startTranslationModelVerification()
        }
    }

    /**
     * 在后台准备共享的英日语音识别模型，以及英语、日语到中文的M2M100翻译模型。
     *
     * 使用方法：
     * 响应用户点击调用。调用立即返回，进度通过[snapshot]观察；如果上一个取消任务仍在清理，
     * 本次调用不会并发启动第二个任务，清理完成后用户可再次重试。ASR包来自固定HTTPS地址，
     * 解压后必须通过精确长度和SHA-256校验才会安装。
     *
     * @return 无返回值。
     */
    fun prepareModels() {
        lateinit var createdJob: Job
        val jobToStart = synchronized(taskLock) {
            if (prepareJob != null) return

            verificationJob?.cancel()
            createdJob = scope.launch(start = CoroutineStart.LAZY) {
                runPreparation()
            }
            pendingPreparationTerminalSnapshot = null
            prepareJob = createdJob
            createdJob.invokeOnCompletion {
                synchronized(taskLock) {
                    val completedCurrentPreparation = prepareJob === createdJob
                    prepareJob = clearTaskIfOwner(prepareJob, createdJob)
                    if (completedCurrentPreparation) {
                        pendingPreparationTerminalSnapshot?.let { terminalSnapshot ->
                            mutableSnapshot.value = terminalSnapshot
                        }
                        pendingPreparationTerminalSnapshot = null
                    }
                }
            }
            createdJob
        }
        jobToStart.start()
    }

    /**
     * 顺序执行ASR下载、原子安装、VAD校验和M2M100固定模型准备。
     *
     * 使用方法：
     * 仅由[prepareModels]创建的独占后台任务调用。取消时保留可安全续传的下载片段。
     *
     * @return 无返回值；失败状态直接发布到[snapshot]，协程取消继续向上抛出。
     */
    private suspend fun runPreparation() {
        var translationPreparationStarted = false
        try {
            modelRoot.mkdirs()
            require(modelRoot.isDirectory) { "Unable to create the private model directory" }

            if (!isCoreAsrModelInstalled()) {
                ensureStorageCapacity()
                mutableSnapshot.value = LiveTranslationModelSnapshot(
                    stage = LiveTranslationModelStage.DOWNLOADING,
                    progressPercent = null,
                    message = "正在下载约163 MB（156 MiB）的识别模型归档，安装后约240 MB"
                )
                if (!isDownloadedArchiveValid()) {
                    if (archiveFile.exists() && !archiveFile.delete()) {
                        throw IllegalStateException("Unable to discard an invalid model archive")
                    }
                    downloadArchive()
                }

                mutableSnapshot.value = LiveTranslationModelSnapshot(
                    stage = LiveTranslationModelStage.INSTALLING,
                    progressPercent = null,
                    message = "正在校验并安装约240 MB的本地识别模型"
                )
                installArchive()
            }

            ensureVadModelInstalled()

            translationPreparationStarted = true
            translationModelsVerifiedReady = null
            val translationAlreadyReady = translationModelRepository.isInstalled()
            val vocabularyOnlyUpgrade = !translationAlreadyReady &&
                translationModelRepository.canReuseInstalledCoreFiles()
            mutableSnapshot.value = LiveTranslationModelSnapshot(
                stage = LiveTranslationModelStage.PREPARING_TRANSLATION,
                progressPercent = null,
                message = when {
                    translationAlreadyReady -> "正在核验M2M100英日到中文本地模型"
                    vocabularyOnlyUpgrade ->
                        "正在增量补充约3.71 MB官方词表，不会重新下载既有1.2 GB权重"
                    else -> "正在准备M2M100高质量英日到中文本地模型（共约1.21 GB）"
                }
            )
            if (!translationAlreadyReady) {
                ensureStorageCapacity(
                    requiredFreeSpaceBytes = if (vocabularyOnlyUpgrade) {
                        INCREMENTAL_TRANSLATION_FREE_SPACE_BYTES
                    } else {
                        REQUIRED_FREE_SPACE_BYTES
                    }
                )
            }
            translationModelRepository.prepare { progress, message ->
                mutableSnapshot.value = LiveTranslationModelSnapshot(
                    stage = LiveTranslationModelStage.PREPARING_TRANSLATION,
                    progressPercent = progress,
                    message = message
                )
            }
            translationModelsVerifiedReady = true

            recordPreparationTerminalSnapshot(readySnapshot())
        } catch (error: CancellationException) {
            if (translationPreparationStarted) {
                translationModelsVerifiedReady = null
            }
            recordPreparationTerminalSnapshot(
                LiveTranslationModelSnapshot(
                    stage = LiveTranslationModelStage.NOT_INSTALLED,
                    message = if (translationPreparationStarted) {
                        "已取消本地翻译模型准备，已下载片段会保留供下次继续"
                    } else {
                        "已取消识别模型准备，已下载片段会保留供下次继续"
                    }
                )
            )
            throw error
        } catch (error: Throwable) {
            translationModelsVerifiedReady = null
            Log.e(TAG, "Failed to prepare live translation models", error)
            recordPreparationTerminalSnapshot(
                LiveTranslationModelSnapshot(
                    stage = LiveTranslationModelStage.ERROR,
                    message = modelPreparationErrorMessage(error)
                )
            )
        }
    }

    /**
     * 暂存准备任务的最终快照，等任务完成回调释放独占权时再原子发布。
     *
     * 使用方法：
     * [runPreparation]成功、失败或取消时调用。这样页面一旦看到可重试状态，旧任务的清理已经结束，
     * 紧接着点击重试不会被仍占用的旧任务静默丢弃，也不会与旧文件操作并发。
     *
     * @param terminalSnapshot 准备任务结束后应向页面发布的READY、ERROR或NOT_INSTALLED快照。
     * @return 无返回值。
     */
    private fun recordPreparationTerminalSnapshot(
        terminalSnapshot: LiveTranslationModelSnapshot
    ) {
        synchronized(taskLock) {
            pendingPreparationTerminalSnapshot = terminalSnapshot
        }
    }

    /**
     * 取消当前模型准备任务并保留可安全续传的下载片段。
     *
     * 使用方法：
     * 页面仅在[snapshot]处于准备阶段时调用。下载片段会保留在应用私有目录；尚未完成校验的安装
     * 临时目录不会被识别为可用模型。
     *
     * @return 无返回值；当前没有任务时重复调用安全。
     */
    fun cancelPreparation() {
        synchronized(taskLock) { prepareJob }?.cancel()
    }

    /**
     * 返回已经安装并通过完成标记检查的ASR模型目录。
     *
     * 使用方法：
     * 仅用于只需要ASR文件的内部场景。前台实时翻译服务启动前应调用
     * [requireReadyModelDirectories]，同时核验M2M100文件与内存门槛。
     *
     * @return 包含model.int8.onnx与tokens.txt的应用私有目录。
     * @throws IllegalStateException 模型文件不完整或尚未准备时抛出。
     */
    fun requireAsrModelDirectory(): File {
        if (!isAsrModelInstalled()) {
            throw LiveTranslationAsrAssetsMissingException()
        }
        return modelDirectory
    }

    /**
     * 在服务启动前一次核验ASR、M2M100文件及运行内存，并返回两个明确目录。
     *
     * 使用方法：
     * 必须在创建[OnDeviceLiveTranslator]和AudioRecord之前调用。函数不联网、不下载；三文件decoder方案
     * 需要较高原生内存，因此总内存不足8 GiB、系统lowMemory或当时可用内存不足2 GiB会直接拒绝启动。
     * 可用内存只是启动保护，不代表实时性能保证。
     *
     * @return ASR目录和可直接传给翻译器构造函数的M2M100目录。
     */
    internal suspend fun requireReadyModelDirectories(): LiveTranslationReadyModelDirectories {
        val verificationToCancel = synchronized(taskLock) {
            if (prepareJob != null) {
                throw LiveTranslationModelPreparationInProgressException()
            }
            verificationJob
        }
        verificationToCancel?.cancel()
        if (!isAsrModelInstalled()) {
            throw LiveTranslationAsrAssetsMissingException()
        }

        val translationReady = try {
            translationModelRepository.isInstalled()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            publishTranslationVerificationError()
            throw error
        }
        publishTranslationVerificationResult(translationReady)
        if (!translationReady) {
            throw LiveTranslationModelAssetsMissingException()
        }
        if (!isAsrModelInstalled()) {
            throw LiveTranslationAsrAssetsMissingException()
        }
        ensureRuntimeMemoryAvailable()
        return LiveTranslationReadyModelDirectories(
            asrDirectory = modelDirectory,
            translationDirectory = translationModelRepository.requireInstalledDirectory()
        )
    }

    /**
     * 兼容只取得ASR目录的旧调用入口。
     *
     * @return 完整就绪与内存核验通过后的ASR目录。
     */
    suspend fun requireReadyModelDirectory(): File {
        return requireReadyModelDirectories().asrDirectory
    }

    /**
     * 根据本地文件和本进程最近一次真实翻译模型核验结果生成快照。
     *
     * @param asrReady 已完成的同步ASR文件检查结果，默认在调用点即时检查。
     * @return 不依赖SharedPreferences布尔标记的当前模型快照。
     */
    private fun readInstalledSnapshot(
        asrReady: Boolean = isAsrModelInstalled()
    ): LiveTranslationModelSnapshot {
        return when {
            !asrReady -> LiveTranslationModelSnapshot(
                stage = LiveTranslationModelStage.NOT_INSTALLED,
                message = "需下载约163 MB识别模型归档；M2M100翻译模型另约1.21 GB"
            )

            translationModelsVerifiedReady == true -> readySnapshot()

            translationModelsVerifiedReady == false -> LiveTranslationModelSnapshot(
                stage = LiveTranslationModelStage.NOT_INSTALLED,
                message = if (translationModelRepository.canReuseInstalledCoreFiles()) {
                    "识别与翻译权重已安装，还需增量补充约3.71 MB官方翻译词表"
                } else {
                    "识别模型已安装，还需准备约1.21 GB的M2M100本地翻译模型"
                }
            )

            else -> LiveTranslationModelSnapshot(
                stage = LiveTranslationModelStage.NOT_INSTALLED,
                message = "正在核验设备上的M2M100英日到中文翻译模型"
            )
        }
    }

    /** @return 可离线执行英日识别与中文翻译的统一READY快照。 */
    private fun readySnapshot(): LiveTranslationModelSnapshot {
        return LiveTranslationModelSnapshot(
            stage = LiveTranslationModelStage.READY,
            progressPercent = 100,
            message = "英语、日语识别及到中文的翻译模型已就绪，运行时全程本地处理"
        )
    }

    /**
     * 启动一次互斥的M2M100本地文件核验，并防止旧任务覆盖正在进行的准备状态。
     *
     * 使用方法：
     * 仅在ASR已经可用且当前没有准备任务时调用。重复调用会复用正在进行的核验，而不会创建多个
     * 文件检查；核验协程结束时只有自身仍是登记任务才允许清空任务引用。
     *
     * @return 无返回值，核验结果异步发布到[snapshot]。
     */
    private fun startTranslationModelVerification() {
        lateinit var createdJob: Job
        val jobToStart = synchronized(taskLock) {
            if (prepareJob != null || verificationJob != null) return

            createdJob = scope.launch(start = CoroutineStart.LAZY) {
                verifyTranslationModelsAndPublish(createdJob)
            }
            verificationJob = createdJob
            createdJob.invokeOnCompletion {
                synchronized(taskLock) {
                    verificationJob = clearTaskIfOwner(verificationJob, createdJob)
                }
            }
            createdJob
        }
        jobToStart.start()
    }

    /**
     * 查询设备真实M2M100安装状态，并且仅在当前核验任务仍有效、没有准备任务时发布结果。
     *
     * @param owner 当前核验协程自身的Job身份，用于阻止已取消或过期任务回写状态。
     * @return 无返回值；查询失败时发布可重试错误，取消时不虚构底层下载已经停止。
     */
    private suspend fun verifyTranslationModelsAndPublish(owner: Job) {
        try {
            val translationReady = translationModelRepository.isInstalled()
            synchronized(taskLock) {
                if (verificationJob !== owner || prepareJob != null) return

                translationModelsVerifiedReady = translationReady
                mutableSnapshot.value = readInstalledSnapshot()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to verify on-device translation models", error)
            synchronized(taskLock) {
                if (verificationJob !== owner || prepareJob != null) return

                translationModelsVerifiedReady = null
                mutableSnapshot.value = translationVerificationErrorSnapshot()
            }
        }
    }

    /**
     * 接收服务启动前的挂起核验结果，并在没有模型准备任务时刷新公开快照。
     *
     * @param translationReady 五个固定M2M100文件与安装标记是否全部可用。
     * @return 无返回值。
     */
    private fun publishTranslationVerificationResult(translationReady: Boolean) {
        synchronized(taskLock) {
            translationModelsVerifiedReady = translationReady
            if (prepareJob == null) {
                mutableSnapshot.value = readInstalledSnapshot()
            }
        }
    }

    /**
     * 处理服务启动前M2M100本地文件核验失败，并避免覆盖仍在运行的准备进度。
     *
     * @return 无返回值；公开快照提示用户重试，不记录或展示字幕正文。
     */
    private fun publishTranslationVerificationError() {
        synchronized(taskLock) {
            translationModelsVerifiedReady = null
            if (prepareJob == null) {
                mutableSnapshot.value = translationVerificationErrorSnapshot()
            }
        }
    }

    /** @return M2M100本地文件无法核验时使用的统一可重试错误快照。 */
    private fun translationVerificationErrorSnapshot(): LiveTranslationModelSnapshot {
        return LiveTranslationModelSnapshot(
            stage = LiveTranslationModelStage.ERROR,
            message = "无法核验M2M100本地翻译模型，请稍后重试"
        )
    }

    /**
     * 检查模型固定文件、精确大小和校验完成标记。
     *
     * @return 所有必需文件均满足安装契约返回true，否则返回false。
     */
    private fun isAsrModelInstalled(): Boolean {
        return isCoreAsrModelInstalled() && isVadModelInstalled()
    }

    /** @return 主模型、词表、许可证及固定版本校验标记完整时返回true，不包含VAD文件。 */
    private fun isCoreAsrModelInstalled(): Boolean {
        val model = File(modelDirectory, MODEL_FILE_NAME)
        val tokens = File(modelDirectory, TOKENS_FILE_NAME)
        val license = File(modelDirectory, LICENSE_FILE_NAME)
        val marker = File(modelDirectory, INSTALL_MARKER_FILE_NAME)
        return model.isFile &&
            model.length() == MODEL_FILE_SIZE_BYTES &&
            tokens.isFile &&
            tokens.length() == TOKENS_FILE_SIZE_BYTES &&
            license.isFile &&
            license.length() == LICENSE_FILE_SIZE_BYTES &&
            runCatching { calculateSha256Blocking(license) == LICENSE_FILE_SHA256 }
                .getOrDefault(false) &&
            marker.isFile &&
            runCatching { marker.readText() == ASR_INSTALL_MARKER_CONTENT }.getOrDefault(false)
    }

    /** @return 固定版本Silero VAD存在、大小与SHA-256均正确时返回true。 */
    private fun isVadModelInstalled(): Boolean {
        val vadModel = File(modelDirectory, VAD_MODEL_FILE_NAME)
        return vadModel.isFile &&
            vadModel.length() == VAD_MODEL_FILE_SIZE_BYTES &&
            runCatching { calculateSha256Blocking(vadModel) == VAD_MODEL_FILE_SHA256 }
                .getOrDefault(false)
    }

    /**
     * 在开始大文件下载前预留归档、解压副本和校验过程所需空间。
     *
     * @param requiredFreeSpaceBytes 当前准备模式要求保留的最小可用字节数；全新安装默认2 GiB，旧模型
     * 增量补词表时由调用方传入较小门槛。
     * @return 无返回值；空间不足时抛出带实际门槛的异常。
     */
    private fun ensureStorageCapacity(
        requiredFreeSpaceBytes: Long = REQUIRED_FREE_SPACE_BYTES
    ) {
        require(requiredFreeSpaceBytes > 0L) { "Required model storage must be positive" }
        if (modelRoot.usableSpace < requiredFreeSpaceBytes) {
            throw InsufficientModelStorageException(requiredFreeSpaceBytes)
        }
    }

    /**
     * 在创建三个大模型Session前执行保守的设备内存门槛检查。
     *
     * @return 无返回值；总内存不足或当前内存压力过高时抛出明确异常，调用方应在AudioRecord前终止会话。
     */
    private fun ensureRuntimeMemoryAvailable() {
        val manager = appContext.getSystemService(ActivityManager::class.java)
            ?: throw LiveTranslationRuntimeMemoryException()
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        if (
            memoryInfo.totalMem < MIN_TOTAL_MEMORY_BYTES ||
            memoryInfo.lowMemory ||
            memoryInfo.availMem < MIN_AVAILABLE_MEMORY_BYTES
        ) {
            throw LiveTranslationRuntimeMemoryException()
        }
    }

    /**
     * 通过HTTPS下载官方tar.bz2归档，并在服务器支持Range时从现有片段继续。
     *
     * @return 无返回值；完整响应写入后把.part原子改名为正式归档。
     */
    private suspend fun downloadArchive() {
        if (partialArchiveFile.length() == MODEL_ARCHIVE_SIZE_BYTES) {
            if (calculateSha256(partialArchiveFile) == MODEL_ARCHIVE_SHA256) {
                check(partialArchiveFile.renameTo(archiveFile)) {
                    "Unable to finalize the existing model archive"
                }
                return
            }
            check(partialArchiveFile.delete()) { "Unable to discard an invalid model download" }
        }
        if (partialArchiveFile.length() > MODEL_ARCHIVE_SIZE_BYTES) {
            check(partialArchiveFile.delete()) { "Unable to discard an invalid model download" }
        }
        val existingBytes = partialArchiveFile.takeIf(File::isFile)?.length() ?: 0L
        val connection = (URL(MODEL_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_CONNECT_TIMEOUT_MILLIS
            readTimeout = NETWORK_READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            val responseCode = connection.responseCode
            val canAppend = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (responseCode !in 200..299) {
                throw IllegalStateException("Model download returned HTTP $responseCode")
            }
            val startingBytes = if (canAppend) existingBytes else 0L
            val totalBytes = MODEL_ARCHIVE_SIZE_BYTES

            BufferedInputStream(connection.inputStream, DOWNLOAD_BUFFER_SIZE_BYTES).use { input ->
                FileOutputStream(partialArchiveFile, canAppend).use { fileOutput ->
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

                            val progress = ((downloadedBytes * 100L) / totalBytes)
                                .toInt()
                                .coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                mutableSnapshot.value = LiveTranslationModelSnapshot(
                                    stage = LiveTranslationModelStage.DOWNLOADING,
                                    progressPercent = progress,
                                    message = "正在下载英日双语本地识别模型（$progress%）"
                                )
                            }
                        }
                    }
                }
            }

            check(partialArchiveFile.length() == MODEL_ARCHIVE_SIZE_BYTES) {
                "Model archive length check failed"
            }
            check(calculateSha256(partialArchiveFile) == MODEL_ARCHIVE_SHA256) {
                "Model archive SHA-256 check failed"
            }

            if (archiveFile.exists() && !archiveFile.delete()) {
                throw IllegalStateException("Unable to replace the completed model archive")
            }
            check(partialArchiveFile.renameTo(archiveFile)) {
                "Unable to finalize the downloaded model archive"
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 校验已完整下载但尚未安装的官方归档。
     *
     * @return 大小与固定SHA-256同时匹配返回true；不存在或损坏返回false。
     */
    private suspend fun isDownloadedArchiveValid(): Boolean {
        return archiveFile.isFile &&
            archiveFile.length() == MODEL_ARCHIVE_SIZE_BYTES &&
            calculateSha256(archiveFile) == MODEL_ARCHIVE_SHA256
    }

    /**
     * 从官方归档仅提取运行所需文件，完整校验主模型、词表和许可证后原子安装。
     *
     * @return 无返回值；任何失败都不会让临时目录被识别为已安装模型。
     */
    private suspend fun installArchive() {
        if (installingDirectory.exists()) installingDirectory.deleteRecursively()
        check(installingDirectory.mkdirs()) { "Unable to create the model installation directory" }

        try {
            val extractedFileNames = mutableSetOf<String>()
            BufferedInputStream(archiveFile.inputStream(), DOWNLOAD_BUFFER_SIZE_BYTES).use { fileInput ->
                BZip2CompressorInputStream(fileInput, true).use { bzipInput ->
                    TarArchiveInputStream(bzipInput).use { tarInput ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tarInput.nextTarEntry ?: break
                            if (!entry.isFile) continue

                            val fileName = entry.name.substringAfterLast('/')
                            if (fileName !in REQUIRED_ARCHIVE_FILE_NAMES) continue
                            check(extractedFileNames.add(fileName)) {
                                "Duplicate required model archive entry"
                            }
                            check(entry.size in 1L..MAX_ARCHIVE_ENTRY_SIZE_BYTES) {
                                "Unexpected model archive entry size"
                            }

                            val destination = File(installingDirectory, fileName)
                            check(destination.canonicalFile.parentFile == installingDirectory.canonicalFile) {
                                "Unsafe model archive path"
                            }
                            copyArchiveEntry(tarInput, destination)
                        }
                    }
                }
            }

            check(extractedFileNames == REQUIRED_ARCHIVE_FILE_NAMES) {
                "Required model files are missing from the archive"
            }
            val model = File(installingDirectory, MODEL_FILE_NAME)
            val tokens = File(installingDirectory, TOKENS_FILE_NAME)
            val license = File(installingDirectory, LICENSE_FILE_NAME)
            check(model.length() == MODEL_FILE_SIZE_BYTES) { "Model file length check failed" }
            check(tokens.length() == TOKENS_FILE_SIZE_BYTES) { "Token file length check failed" }
            check(license.length() == LICENSE_FILE_SIZE_BYTES) { "License file length check failed" }
            check(calculateSha256(model) == MODEL_FILE_SHA256) { "Model SHA-256 check failed" }
            check(calculateSha256(tokens) == TOKENS_FILE_SHA256) { "Token file SHA-256 check failed" }
            check(calculateSha256(license) == LICENSE_FILE_SHA256) {
                "License SHA-256 check failed"
            }
            File(installingDirectory, INSTALL_MARKER_FILE_NAME)
                .writeText(ASR_INSTALL_MARKER_CONTENT)

            if (modelDirectory.exists() && !modelDirectory.deleteRecursively()) {
                throw IllegalStateException("Unable to replace the previous model directory")
            }
            check(installingDirectory.renameTo(modelDirectory)) {
                "Unable to finalize the installed model directory"
            }
            archiveFile.delete()
            partialArchiveFile.delete()
        } finally {
            if (installingDirectory.exists()) installingDirectory.deleteRecursively()
        }
    }

    /**
     * 下载并校验与当前sherpa-onnx接口匹配的官方Silero VAD模型。
     *
     * 使用方法：
     * 主ASR目录安装后自动调用。VAD文件不足1MB，不做断点续传；中断时保留的.part会在下次准备前
     * 重新覆盖。只有精确大小和SHA-256均匹配才会进入正式模型目录。
     *
     * @return 无返回值。
     */
    private suspend fun ensureVadModelInstalled() {
        if (isVadModelInstalled()) return

        val destination = File(modelDirectory, VAD_MODEL_FILE_NAME)
        val partial = File(modelRoot, "$VAD_MODEL_FILE_NAME.part")
        val connection = (URL(VAD_MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_CONNECT_TIMEOUT_MILLIS
            readTimeout = NETWORK_READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("VAD model download returned HTTP $responseCode")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(partial.outputStream()).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val readCount = input.read(buffer)
                        if (readCount < 0) break
                        output.write(buffer, 0, readCount)
                    }
                }
            }
            check(partial.length() == VAD_MODEL_FILE_SIZE_BYTES) { "VAD model length check failed" }
            check(calculateSha256(partial) == VAD_MODEL_FILE_SHA256) {
                "VAD model SHA-256 check failed"
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Unable to replace the VAD model")
            }
            check(partial.renameTo(destination)) { "Unable to install the VAD model" }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 从当前tar条目复制到目标文件，并在每个缓冲区边界响应协程取消。
     *
     * @param tarInput 已定位到普通文件条目的tar输入流。
     * @param destination 应用私有安装临时目录中的目标文件。
     * @return 无返回值。
     */
    private suspend fun copyArchiveEntry(
        tarInput: TarArchiveInputStream,
        destination: File
    ) {
        BufferedOutputStream(destination.outputStream(), DOWNLOAD_BUFFER_SIZE_BYTES).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val readCount = tarInput.read(buffer)
                if (readCount < 0) break
                output.write(buffer, 0, readCount)
            }
        }
    }

    /**
     * 以流式读取方式计算大文件SHA-256，避免把模型一次载入Java堆。
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
     * 对很小的固定辅助模型同步计算SHA-256，用于页面刷新时发现文件损坏。
     *
     * @param file 小型VAD模型文件。
     * @return 小写十六进制SHA-256。
     */
    private fun calculateSha256Blocking(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE_BYTES)
            while (true) {
                val readCount = input.read(buffer)
                if (readCount < 0) break
                digest.update(buffer, 0, readCount)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** @return 把内部异常转换为不泄漏路径、URL或字幕内容的中文重试提示。 */
    private fun modelPreparationErrorMessage(error: Throwable): String {
        return when (error) {
            is InsufficientModelStorageException -> if (
                error.requiredFreeSpaceBytes <= INCREMENTAL_TRANSLATION_FREE_SPACE_BYTES
            ) {
                "存储空间不足，请至少留出16 MiB可用空间以补充翻译词表"
            } else {
                "存储空间不足，请至少留出2 GiB可用空间后重试"
            }

            else -> "本地模型准备失败，请检查网络、存储空间后重试"
        }
    }

    /**
     * 关闭进程级后台作用域，仅供测试或应用进程明确清理时调用。
     *
     * @return 无返回值；正常应用生命周期不需要主动调用。
     */
    internal fun close() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "LiveModelManager"
        private const val MODEL_ROOT_DIRECTORY_NAME = "live_translation_models"
        private const val MODEL_DIRECTORY_NAME =
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        private const val MODEL_PACKAGE_VERSION = MODEL_DIRECTORY_NAME
        private const val MODEL_ARCHIVE_FILE_NAME = "$MODEL_DIRECTORY_NAME.tar.bz2"
        private const val MODEL_FILE_NAME = "model.int8.onnx"
        private const val TOKENS_FILE_NAME = "tokens.txt"
        private const val LICENSE_FILE_NAME = "LICENSE"
        private const val INSTALL_MARKER_FILE_NAME = ".verified"
        private const val ASR_INSTALL_MARKER_SCHEMA_VERSION = 2
        private const val MODEL_ARCHIVE_SIZE_BYTES = 163_002_883L
        private const val MODEL_ARCHIVE_SHA256 =
            "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        private const val MODEL_FILE_SIZE_BYTES = 239_233_841L
        private const val MODEL_FILE_SHA256 =
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        private const val TOKENS_FILE_SIZE_BYTES = 315_894L
        private const val TOKENS_FILE_SHA256 =
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        private const val LICENSE_FILE_SIZE_BYTES = 71L
        private const val LICENSE_FILE_SHA256 =
            "221c6df10b0931a5629adad671ea48fb7747e034c414b6d2bfa275bc3dd4ea17"
        private const val VAD_MODEL_FILE_NAME = "silero_vad.onnx"
        private const val VAD_MODEL_FILE_SIZE_BYTES = 643_854L
        private const val VAD_MODEL_FILE_SHA256 =
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        private const val MAX_ARCHIVE_ENTRY_SIZE_BYTES = 300L * 1024L * 1024L
        private const val REQUIRED_FREE_SPACE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val INCREMENTAL_TRANSLATION_FREE_SPACE_BYTES = 16L * 1024L * 1024L
        private const val MIN_TOTAL_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MIN_AVAILABLE_MEMORY_BYTES = 2L * 1024L * 1024L * 1024L
        private const val NETWORK_CONNECT_TIMEOUT_MILLIS = 20_000
        private const val NETWORK_READ_TIMEOUT_MILLIS = 45_000
        private const val DOWNLOAD_BUFFER_SIZE_BYTES = 64 * 1024
        private const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
        private const val VAD_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        private val REQUIRED_ARCHIVE_FILE_NAMES = setOf(
            MODEL_FILE_NAME,
            TOKENS_FILE_NAME,
            LICENSE_FILE_NAME
        )
        private val ASR_INSTALL_MARKER_CONTENT = buildAsrInstallMarker(
            schemaVersion = ASR_INSTALL_MARKER_SCHEMA_VERSION,
            modelVersion = MODEL_PACKAGE_VERSION,
            archiveSha256 = MODEL_ARCHIVE_SHA256,
            modelSha256 = MODEL_FILE_SHA256,
            tokensSha256 = TOKENS_FILE_SHA256,
            licenseSha256 = LICENSE_FILE_SHA256
        )

        @Volatile
        private var instance: LiveTranslationModelManager? = null

        /**
         * 返回应用进程共享的模型管理器。
         *
         * @param context 任意Android上下文，内部只保留applicationContext。
         * @return 单一模型管理器实例。
         */
        fun get(context: Context): LiveTranslationModelManager {
            return instance ?: synchronized(this) {
                instance ?: LiveTranslationModelManager(context).also { created ->
                    instance = created
                }
            }
        }
    }
}

/**
 * 生成与固定ASR发行版本及其所有归档文件哈希绑定的安装完成标记。
 *
 * 使用方法：
 * 安装器在全部文件校验成功后把返回值原样写入`.verified`；启动检查也使用相同参数重新生成并做
 * 完整字符串比较。任一发行版本或文件哈希变化都会使旧标记失效，防止只凭一个主模型哈希误认安装。
 *
 * @param schemaVersion 标记格式版本，修改字段结构时递增。
 * @param modelVersion 固定模型发行目录名称或等价不可变版本号。
 * @param archiveSha256 官方压缩归档的小写SHA-256。
 * @param modelSha256 识别主模型文件的小写SHA-256。
 * @param tokensSha256 词表文件的小写SHA-256。
 * @param licenseSha256 许可证文件的小写SHA-256。
 * @return 字段顺序固定、没有末尾换行的安装完成标记正文。
 */
internal fun buildAsrInstallMarker(
    schemaVersion: Int,
    modelVersion: String,
    archiveSha256: String,
    modelSha256: String,
    tokensSha256: String,
    licenseSha256: String
): String {
    require(schemaVersion > 0) { "ASR install marker schema version must be positive" }
    require(modelVersion.isNotBlank()) { "ASR model version must not be blank" }

    return listOf(
        "schema_version=$schemaVersion",
        "model_version=$modelVersion",
        "archive_sha256=$archiveSha256",
        "model_sha256=$modelSha256",
        "tokens_sha256=$tokensSha256",
        "license_sha256=$licenseSha256"
    ).joinToString(separator = "\n")
}

/**
 * 仅当结束回调属于当前登记任务时清空任务引用。
 *
 * 使用方法：
 * 异步任务完成回调在同一把锁内传入当前登记值和自身身份。旧任务迟到的回调会保留新任务，避免
 * “取消后立即重试”时旧回调把新任务清空并允许第三个任务并发进入。
 *
 * @param current 当前登记的任务对象，可能为空或已经替换成新任务。
 * @param completed 正在执行结束回调的任务对象。
 * @return 两者为同一对象时返回null，否则原样返回[current]。
 */
internal fun <T : Any> clearTaskIfOwner(current: T?, completed: T): T? {
    return if (current === completed) null else current
}

/** ASR主模型、词表、许可证、完成标记或VAD任一缺失或损坏。 */
internal class LiveTranslationAsrAssetsMissingException : IllegalStateException(
    "Live translation ASR assets are missing or invalid"
)

/** 用户触发的模型准备任务尚未完成，服务不能同时消费正在变化的模型文件。 */
internal class LiveTranslationModelPreparationInProgressException : IllegalStateException(
    "Live translation model preparation is still running"
)

/** 三文件M2M100推理所需总内存或当前可用内存不足，应关闭高负载应用后重试。 */
internal class LiveTranslationRuntimeMemoryException : IllegalStateException(
    "Insufficient memory for local M2M100 translation"
)

/** 模型准备前发现应用私有存储不足。 */
private class InsufficientModelStorageException(
    val requiredFreeSpaceBytes: Long
) : IllegalStateException()

/**
 * 服务启动前一次取得的两个已核验目录。
 *
 * @param asrDirectory SenseVoice主模型、词表及Silero VAD所在目录。
 * @param translationDirectory M2M100三个ONNX文件、SentencePiece模型和官方JSON词表所在目录。
 */
internal data class LiveTranslationReadyModelDirectories(
    val asrDirectory: File,
    val translationDirectory: File
)
