package com.example.harleyapp.web

import android.webkit.WebView
import com.example.harleyapp.model.WebsiteToolSettings
import java.util.Locale

/**
 * 向当前WebView主文档及可访问的同源iframe注入网页增强脚本。
 *
 * 使用方法：
 * 页面完成加载或设置变化时调用[applySettings]；用户点击媒体按钮时调用[togglePlayback]、
 * [seekBy]或[requestFullscreen]。本类不注册JavaScriptInterface，不把网页数据传回Android，
 * 只通过evaluateJavascript返回已找到的媒体数量。
 */
class WebsiteScriptController {

    /**
     * 应用倍速、自动播放、静音、循环、文字选择和夜间遮罩设置。
     *
     * 脚本会安装MutationObserver和低频检查器，让单页应用后续创建的视频继续使用用户设置。
     * 跨域iframe受浏览器同源策略限制，无法访问时会安全跳过。
     *
     * @param webView 当前网站WebView。
     * @param settings 已规范化的网页工具设置。
     * @param onMediaCountApplied 脚本完成后返回当前找到的音视频元素数量。
     * @return 无返回值。
     */
    fun applySettings(
        webView: WebView,
        settings: WebsiteToolSettings,
        onMediaCountApplied: (Int) -> Unit = {}
    ) {
        val safeSettings = settings.normalized()
        val script = APPLY_SETTINGS_SCRIPT.format(
            Locale.US,
            safeSettings.playbackRate,
            safeSettings.blockAutoplay,
            safeSettings.videoMuted,
            safeSettings.videoLoopEnabled,
            safeSettings.textSelectionEnabled,
            safeSettings.nightModeEnabled,
            safeSettings.nightOverlayAlpha
        )
        evaluateMediaScript(webView, script, onMediaCountApplied)
    }

    /**
     * 切换当前主要视频或音频的播放与暂停状态。
     *
     * @param webView 当前网站WebView。
     * @param onMediaCount 找到可控制媒体时返回1，否则返回0。
     * @return 无返回值。
     */
    fun togglePlayback(webView: WebView, onMediaCount: (Int) -> Unit) {
        evaluateMediaScript(webView, mediaActionScript(MEDIA_TOGGLE_BODY), onMediaCount)
    }

    /**
     * 快退或快进当前主要媒体。
     *
     * @param webView 当前网站WebView。
     * @param seconds 相对跳转秒数，负数表示快退，正数表示快进；限制为正负300秒。
     * @param onMediaCount 找到可控制媒体时返回1，否则返回0。
     * @return 无返回值。
     */
    fun seekBy(webView: WebView, seconds: Int, onMediaCount: (Int) -> Unit) {
        val safeSeconds = seconds.coerceIn(-MAX_SEEK_SECONDS, MAX_SEEK_SECONDS)
        val action = MEDIA_SEEK_BODY.format(Locale.US, safeSeconds, safeSeconds)
        evaluateMediaScript(webView, mediaActionScript(action), onMediaCount)
    }

    /**
     * 开始全屏左半屏长按对应的三倍速连续回退。
     *
     * 使用方法：全屏手势达到系统长按阈值后调用一次，抬手、取消、退出全屏或销毁页面时必须配对
     * 调用[endFullscreenHold]。标准HTML媒体不可靠支持负播放倍速，因此脚本会暂时暂停正在播放的
     * 媒体，再按真实经过时间持续回拨进度；结束后恢复按下前的播放或暂停状态。
     *
     * @param webView 当前网站WebView。
     * @param onMediaCount 找到并开始控制主要媒体时返回1，否则返回0。
     * @return 无返回值。
     */
    fun beginFullscreenHoldRewind(webView: WebView, onMediaCount: (Int) -> Unit) {
        val action = MEDIA_HOLD_REWIND_BODY.format(
            Locale.US,
            FULLSCREEN_HOLD_SPEED_MULTIPLIER,
            HOLD_REWIND_INTERVAL_MILLIS
        )
        evaluateMediaScript(webView, fullscreenHoldActionScript(action), onMediaCount)
    }

    /**
     * 开始全屏右半屏长按对应的临时三倍速播放。
     *
     * 使用方法：全屏手势达到系统长按阈值后调用一次，结束手势时必须调用[endFullscreenHold]。
     * 临时倍速只保存在当前页面JavaScript上下文，不写入网站工具设置；松手时会恢复用户原先保存的
     * 倍速，即使网页工具的定时校正或ratechange监听正在运行也不会提前覆盖本次长按状态。
     *
     * @param webView 当前网站WebView。
     * @param onMediaCount 找到并开始控制主要媒体时返回1，否则返回0。
     * @return 无返回值。
     */
    fun beginFullscreenHoldFastForward(webView: WebView, onMediaCount: (Int) -> Unit) {
        val action = MEDIA_HOLD_FAST_FORWARD_BODY.format(
            Locale.US,
            FULLSCREEN_HOLD_SPEED_MULTIPLIER
        )
        evaluateMediaScript(webView, fullscreenHoldActionScript(action), onMediaCount)
    }

    /**
     * 结束当前全屏长按媒体操作并恢复开始前的稳定状态。
     *
     * 使用方法：所有抬手、触摸取消、全屏退出、标签切换和View解绑路径均可直接调用。本函数幂等，
     * 页面中没有活动长按时返回0且不会改变媒体状态；存在活动长按时停止回退计时器，恢复原播放状态
     * 或用户保存的播放倍速后返回1。
     *
     * @param webView 当前网站WebView。
     * @param restorePlayback true允许左侧回退结束后恢复按下前的播放状态；切换标签、进入后台或销毁
     * 页面时应传false，确保清理不会重新启动已经暂停的媒体。右侧快进无论取值都会恢复原倍速。
     * @param onEnded 页面存在活动长按并完成清理时返回1，否则返回0。
     * @return 无返回值。
     */
    fun endFullscreenHold(
        webView: WebView,
        restorePlayback: Boolean = true,
        onEnded: (Int) -> Unit = {}
    ) {
        val script = END_FULLSCREEN_HOLD_SCRIPT_TEMPLATE.format(Locale.US, restorePlayback)
        evaluateMediaScript(webView, script, onEnded)
    }

    /**
     * 请求当前主要视频进入HTML5全屏。
     *
     * @param webView 当前网站WebView。
     * @param onMediaCount 找到视频并发起请求时返回1，否则返回0。网站仍可按自身策略拒绝请求。
     * @return 无返回值。
     */
    fun requestFullscreen(webView: WebView, onMediaCount: (Int) -> Unit) {
        evaluateMediaScript(webView, mediaActionScript(MEDIA_FULLSCREEN_BODY), onMediaCount)
    }

    /**
     * 查询当前页面正在播放的视频数量。
     *
     * 使用方法：
     * WebsiteScreen可见期间低频调用，用于只在视频真正开始播放后自动显示脚本工具栏。查询优先复用
     * 已注入的网页工具对象，因此同源iframe中的视频也可统计；没有注入对象时安全回退到当前文档。
     *
     * @param webView 当前网站WebView。
     * @param onPlayingVideoCount 返回未暂停且未播放结束的视频数量；脚本异常时返回0。
     *
     * @return 无返回值，结果通过回调交付。
     */
    fun queryPlayingVideoCount(
        webView: WebView,
        onPlayingVideoCount: (Int) -> Unit
    ) {
        evaluateMediaScript(
            webView = webView,
            script = PLAYING_VIDEO_COUNT_SCRIPT,
            callback = onPlayingVideoCount
        )
    }

    /**
     * 包装一次针对当前主要媒体的操作。
     *
     * @param actionBody 找到media变量后执行的JavaScript语句。
     * @return 完整自执行脚本，执行成功返回1，无媒体返回0。
     */
    private fun mediaActionScript(actionBody: String): String {
        return MEDIA_ACTION_TEMPLATE.replace(ACTION_PLACEHOLDER, actionBody)
    }

    /**
     * 包装一次互斥的全屏长按媒体操作。
     *
     * @param actionBody 找到media变量后启动长按会话的JavaScript语句。
     * @return 完整自执行脚本；开始前会先幂等结束旧会话，找到媒体返回1，否则返回0。
     */
    private fun fullscreenHoldActionScript(actionBody: String): String {
        return FULLSCREEN_HOLD_ACTION_TEMPLATE.replace(ACTION_PLACEHOLDER, actionBody)
    }

    /**
     * 执行只返回媒体数量的脚本，并把WebView字符串结果转换为整数。
     *
     * @param webView 当前网站WebView。
     * @param script 待执行JavaScript。
     * @param callback 解析后的媒体数量回调。
     * @return 无返回值。
     */
    private fun evaluateMediaScript(
        webView: WebView,
        script: String,
        callback: (Int) -> Unit
    ) {
        webView.evaluateJavascript(script) { result ->
            callback(result.trim().trim('"').toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
    }

    private companion object {
        const val MAX_SEEK_SECONDS = 300
        const val FULLSCREEN_HOLD_SPEED_MULTIPLIER = 3.0f
        const val HOLD_REWIND_INTERVAL_MILLIS = 100
        const val ACTION_PLACEHOLDER = "__HARLEY_ACTION__"

        val MEDIA_ACTION_TEMPLATE = """
            (function() {
                var tools = window.__harleyWebToolsV1;
                var media = tools && tools.activeMedia ? tools.activeMedia() : null;
                if (!media) {
                    var items = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
                    media = items.sort(function(a, b) {
                        var ar = a.getBoundingClientRect();
                        var br = b.getBoundingClientRect();
                        return (br.width * br.height) - (ar.width * ar.height);
                    })[0] || null;
                }
                if (!media) return 0;
                __HARLEY_ACTION__
                return 1;
            })();
        """.trimIndent()

        val FULLSCREEN_HOLD_ACTION_TEMPLATE = """
            (function() {
                var previousHold = window.__harleyFullscreenHoldV1;
                if (previousHold && typeof previousHold.stop === 'function') {
                    previousHold.stop();
                }
                var tools = window.__harleyWebToolsV1;
                var documents = tools && tools.documents ? tools.documents() : [document];
                var media = null;
                var hasFullscreenElement = false;
                var directFullscreenVideo = null;
                var fullscreenVideos = [];

                function elementTreeVisible(element) {
                    var current = element;
                    var view = element && element.ownerDocument
                        ? element.ownerDocument.defaultView
                        : null;
                    while (current && current.nodeType === 1) {
                        var style = view && view.getComputedStyle ? view.getComputedStyle(current) : null;
                        if (current.hidden ||
                            (style && (
                                style.display === 'none' ||
                                style.visibility === 'hidden' ||
                                style.visibility === 'collapse' ||
                                parseFloat(style.opacity || '1') <= 0.01
                            ))) {
                            return false;
                        }
                        current = current.parentElement;
                    }
                    return true;
                }

                function visibleVideoArea(video) {
                    if (!video || !video.isConnected || !elementTreeVisible(video)) return 0;
                    try {
                        var doc = video.ownerDocument || document;
                        var view = doc.defaultView || window;
                        var rect = video.getBoundingClientRect();
                        var viewportWidth = Math.max(
                            doc.documentElement ? doc.documentElement.clientWidth : 0,
                            view.innerWidth || 0
                        );
                        var viewportHeight = Math.max(
                            doc.documentElement ? doc.documentElement.clientHeight : 0,
                            view.innerHeight || 0
                        );
                        var visibleWidth = Math.max(
                            0,
                            Math.min(rect.right, viewportWidth) - Math.max(rect.left, 0)
                        );
                        var visibleHeight = Math.max(
                            0,
                            Math.min(rect.bottom, viewportHeight) - Math.max(rect.top, 0)
                        );
                        if (visibleWidth <= 0 || visibleHeight <= 0) return 0;

                        var frame = view.frameElement;
                        while (frame) {
                            if (!elementTreeVisible(frame)) return 0;
                            var frameDoc = frame.ownerDocument;
                            var frameView = frameDoc.defaultView || window;
                            var frameRect = frame.getBoundingClientRect();
                            var frameViewportWidth = Math.max(
                                frameDoc.documentElement ? frameDoc.documentElement.clientWidth : 0,
                                frameView.innerWidth || 0
                            );
                            var frameViewportHeight = Math.max(
                                frameDoc.documentElement ? frameDoc.documentElement.clientHeight : 0,
                                frameView.innerHeight || 0
                            );
                            if (frameRect.right <= 0 ||
                                frameRect.bottom <= 0 ||
                                frameRect.left >= frameViewportWidth ||
                                frameRect.top >= frameViewportHeight
                            ) {
                                return 0;
                            }
                            frame = frameView.frameElement;
                        }
                        return visibleWidth * visibleHeight;
                    } catch (ignored) {
                        return 0;
                    }
                }

                function selectVideo(candidates, requirePlaying) {
                    var ranked = [];
                    candidates.forEach(function(video) {
                        if (ranked.some(function(entry) { return entry.video === video; })) return;
                        var area = visibleVideoArea(video);
                        if (area <= 0) return;
                        ranked.push({
                            video: video,
                            area: area,
                            playing: !video.paused && !video.ended
                        });
                    });
                    var playing = ranked.filter(function(entry) { return entry.playing; });
                    var pool = playing.length ? playing : (requirePlaying ? [] : ranked);
                    pool.sort(function(a, b) { return b.area - a.area; });
                    return pool.length ? pool[0].video : null;
                }

                documents.forEach(function(doc) {
                    var fullscreenElement = doc.fullscreenElement || doc.webkitFullscreenElement || null;
                    if (!fullscreenElement) return;
                    hasFullscreenElement = true;
                    if (fullscreenElement.tagName && fullscreenElement.tagName.toLowerCase() === 'video') {
                        directFullscreenVideo = fullscreenElement;
                    } else if (fullscreenElement.querySelector) {
                        fullscreenVideos = fullscreenVideos.concat(
                            Array.prototype.slice.call(fullscreenElement.querySelectorAll('video'))
                        );
                    }
                });
                media = directFullscreenVideo || selectVideo(fullscreenVideos, false);
                if (hasFullscreenElement && !media) return 0;
                if (!media) {
                    var videos = [];
                    documents.forEach(function(doc) {
                        videos = videos.concat(Array.prototype.slice.call(doc.querySelectorAll('video')));
                    });
                    media = selectVideo(videos, true);
                }
                if (!media) return 0;
                __HARLEY_ACTION__
            })();
        """.trimIndent()

        val PLAYING_VIDEO_COUNT_SCRIPT = """
            (function() {
                var tools = window.__harleyWebToolsV1;
                var items = [];
                if (tools && tools.documents) {
                    tools.documents().forEach(function(doc) {
                        items = items.concat(Array.prototype.slice.call(doc.querySelectorAll('video')));
                    });
                } else {
                    items = Array.prototype.slice.call(document.querySelectorAll('video'));
                }
                return items.filter(function(video) {
                    return !video.paused && !video.ended && video.readyState > 1;
                }).length;
            })();
        """.trimIndent()

        const val MEDIA_TOGGLE_BODY = "if (media.paused) { media.play(); } else { media.pause(); }"
        const val MEDIA_SEEK_BODY =
            "media.currentTime = Math.max(0, Math.min(isFinite(media.duration) ? media.duration : media.currentTime + (%d), media.currentTime + (%d)));"
        val MEDIA_HOLD_REWIND_BODY = """
            if (!isFinite(media.currentTime)) return 0;
            var initialSeekFloor = 0;
            var hasSeekableRange = false;
            try {
                if (media.seekable && media.seekable.length > 0) {
                    initialSeekFloor = media.seekable.start(0);
                    hasSeekableRange = true;
                }
            } catch (ignored) {}
            var hasFiniteTimeline = isFinite(media.duration) && media.duration > 0;
            if (!hasSeekableRange && !hasFiniteTimeline) return 0;
            if (media.currentTime <= initialSeekFloor + 0.01) return 0;
            var rewindStartTime = media.currentTime;
            try {
                media.currentTime = rewindStartTime;
            } catch (ignored) {
                return 0;
            }
            var resumeAfterStop = !media.paused;
            if (resumeAfterStop) {
                try {
                    media.pause();
                } catch (ignored) {
                    return 0;
                }
                if (!media.paused) return 0;
            }
            var hold = {
                media: media,
                mode: 'rewind',
                rate: %.2f,
                intervalId: null,
                stopped: false,
                resumeAfterStop: resumeAfterStop,
                startMediaTime: rewindStartTime,
                startedAt: performance.now(),
                stop: function(restorePlayback) {
                    if (this.stopped) return;
                    this.stopped = true;
                    if (this.intervalId !== null) {
                        window.clearInterval(this.intervalId);
                        this.intervalId = null;
                    }
                    if (window.__harleyFullscreenHoldV1 === this) {
                        window.__harleyFullscreenHoldV1 = null;
                    }
                    if (restorePlayback !== false &&
                        this.resumeAfterStop &&
                        this.media &&
                        this.media.isConnected &&
                        this.media.paused) {
                        try {
                            var resumed = this.media.play();
                            if (resumed && resumed.catch) resumed.catch(function() {});
                        } catch (ignored) {}
                    }
                }
            };
            window.__harleyFullscreenHoldV1 = hold;
            try {
                hold.intervalId = window.setInterval(function() {
                    if (hold.stopped || window.__harleyFullscreenHoldV1 !== hold) return;
                    if (!media.isConnected) {
                        hold.stop();
                        return;
                    }
                    if (!isFinite(media.currentTime)) {
                        hold.stop();
                        return;
                    }
                    var seekFloor = 0;
                    try {
                        if (media.seekable && media.seekable.length > 0) {
                            seekFloor = media.seekable.start(0);
                        }
                    } catch (ignored) {}
                    var elapsedSeconds = Math.max((performance.now() - hold.startedAt) / 1000, 0);
                    var targetTime = Math.max(
                        seekFloor,
                        hold.startMediaTime - (elapsedSeconds * hold.rate)
                    );
                    try {
                        media.currentTime = targetTime;
                    } catch (ignored) {
                        hold.stop();
                    }
                }, %d);
            } catch (ignored) {
                hold.stop();
                return 0;
            }
            return 1;
        """.trimIndent()
        val MEDIA_HOLD_FAST_FORWARD_BODY = """
            if (media.paused || media.ended) return 0;
            var tools = window.__harleyWebToolsV1;
            var configuredRate = tools && tools.config && isFinite(tools.config.playbackRate)
                ? tools.config.playbackRate
                : media.playbackRate;
            var hold = {
                media: media,
                mode: 'fast-forward',
                rate: %.2f,
                restoreRate: configuredRate,
                stopped: false,
                stop: function() {
                    if (this.stopped) return;
                    this.stopped = true;
                    if (window.__harleyFullscreenHoldV1 === this) {
                        window.__harleyFullscreenHoldV1 = null;
                    }
                    var activeTools = window.__harleyWebToolsV1;
                    var targetRate = activeTools && activeTools.config && isFinite(activeTools.config.playbackRate)
                        ? activeTools.config.playbackRate
                        : this.restoreRate;
                    if (activeTools) activeTools.applyingRate = true;
                    try {
                        this.media.defaultPlaybackRate = targetRate;
                        this.media.playbackRate = targetRate;
                    } catch (ignored) {}
                    if (activeTools) activeTools.applyingRate = false;
                }
            };
            window.__harleyFullscreenHoldV1 = hold;
            if (tools) tools.applyingRate = true;
            try {
                media.playbackRate = hold.rate;
            } catch (ignored) {
                hold.stop(false);
                if (tools) tools.applyingRate = false;
                return 0;
            }
            if (tools) tools.applyingRate = false;
            if (Math.abs(media.playbackRate - hold.rate) > 0.01) {
                hold.stop(false);
                return 0;
            }
            return 1;
        """.trimIndent()
        val END_FULLSCREEN_HOLD_SCRIPT_TEMPLATE = """
            (function() {
                var hold = window.__harleyFullscreenHoldV1;
                if (!hold || typeof hold.stop !== 'function') return 0;
                hold.stop(%b);
                return 1;
            })();
        """.trimIndent()
        val MEDIA_FULLSCREEN_BODY = """
            if (media.requestFullscreen) {
                var request = media.requestFullscreen();
                if (request && request.catch) request.catch(function() {});
            } else if (media.webkitRequestFullscreen) {
                media.webkitRequestFullscreen();
            } else if (media.webkitEnterFullscreen) {
                media.webkitEnterFullscreen();
            }
        """.trimIndent()

        val APPLY_SETTINGS_SCRIPT = """
            (function() {
                var nextConfig = {
                    playbackRate: %.2f,
                    blockAutoplay: %b,
                    muted: %b,
                    loop: %b,
                    textSelection: %b,
                    nightMode: %b,
                    nightAlpha: %.2f
                };
                var tools = window.__harleyWebToolsV1;
                if (!tools) {
                    tools = {
                        config: nextConfig,
                        applyingRate: false,
                        documents: function() {
                            var docs = [document];
                            Array.prototype.forEach.call(document.querySelectorAll('iframe'), function(frame) {
                                try {
                                    if (frame.contentDocument) docs.push(frame.contentDocument);
                                } catch (ignored) {}
                            });
                            return docs;
                        },
                        mediaItems: function() {
                            var items = [];
                            this.documents().forEach(function(doc) {
                                items = items.concat(Array.prototype.slice.call(doc.querySelectorAll('video, audio')));
                            });
                            return items;
                        },
                        activeMedia: function() {
                            var items = this.mediaItems();
                            var playing = items.filter(function(item) { return !item.paused && !item.ended; });
                            var candidates = playing.length ? playing : items;
                            return candidates.sort(function(a, b) {
                                var ar = a.getBoundingClientRect();
                                var br = b.getBoundingClientRect();
                                return (br.width * br.height) - (ar.width * ar.height);
                            })[0] || null;
                        },
                        applyDocumentStyles: function(doc) {
                            var selectStyleId = 'harley-text-selection-style';
                            var selectStyle = doc.getElementById(selectStyleId);
                            if (this.config.textSelection) {
                                if (!selectStyle) {
                                    selectStyle = doc.createElement('style');
                                    selectStyle.id = selectStyleId;
                                    (doc.head || doc.documentElement).appendChild(selectStyle);
                                }
                                var selectionCss = 'html,body,body *{-webkit-user-select:text!important;user-select:text!important;}';
                                if (selectStyle.textContent !== selectionCss) {
                                    selectStyle.textContent = selectionCss;
                                }
                            } else if (selectStyle) {
                                selectStyle.remove();
                            }

                            var overlayId = 'harley-night-overlay';
                            var overlay = doc.getElementById(overlayId);
                            if (this.config.nightMode) {
                                if (!overlay) {
                                    overlay = doc.createElement('div');
                                    overlay.id = overlayId;
                                    (doc.documentElement || doc.body).appendChild(overlay);
                                }
                                overlay.setAttribute('style', 'position:fixed!important;inset:0!important;background:rgba(0,0,0,' +
                                    this.config.nightAlpha + ')!important;pointer-events:none!important;z-index:2147483646!important;');
                            } else if (overlay) {
                                overlay.remove();
                            }
                        },
                        apply: function() {
                            var self = this;
                            this.documents().forEach(function(doc) { self.applyDocumentStyles(doc); });
                            var items = this.mediaItems();
                            items.forEach(function(media) {
                                var hold = window.__harleyFullscreenHoldV1;
                                var expectedRate = hold && hold.mode === 'fast-forward' && hold.media === media
                                    ? hold.rate
                                    : self.config.playbackRate;
                                media.defaultPlaybackRate = self.config.playbackRate;
                                if (Math.abs(media.playbackRate - expectedRate) > 0.001) {
                                    self.applyingRate = true;
                                    try { media.playbackRate = expectedRate; } catch (ignored) {}
                                    self.applyingRate = false;
                                }
                                media.muted = self.config.muted;
                                media.loop = self.config.loop;
                                if (self.config.blockAutoplay && media.hasAttribute('autoplay')) {
                                    media.autoplay = false;
                                    media.removeAttribute('autoplay');
                                    media.pause();
                                }
                                if (!media.__harleyRateBound) {
                                    media.__harleyRateBound = true;
                                    media.addEventListener('loadedmetadata', function() { self.apply(); }, true);
                                    media.addEventListener('play', function() { self.apply(); }, true);
                                    media.addEventListener('ratechange', function() {
                                        var activeHold = window.__harleyFullscreenHoldV1;
                                        var enforcedRate = activeHold &&
                                            activeHold.mode === 'fast-forward' &&
                                            activeHold.media === media
                                            ? activeHold.rate
                                            : self.config.playbackRate;
                                        if (!self.applyingRate &&
                                            Math.abs(media.playbackRate - enforcedRate) > 0.001) {
                                            try { media.playbackRate = enforcedRate; } catch (ignored) {}
                                        }
                                    }, true);
                                }
                            });
                            return items.length;
                        }
                    };
                    window.__harleyWebToolsV1 = tools;
                    var observer = new MutationObserver(function() { tools.apply(); });
                    observer.observe(document.documentElement || document, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['autoplay']
                    });
                    tools.observer = observer;
                    tools.intervalId = window.setInterval(function() { tools.apply(); }, 1000);
                }
                tools.config = nextConfig;
                return tools.apply();
            })();
        """.trimIndent()
    }
}
