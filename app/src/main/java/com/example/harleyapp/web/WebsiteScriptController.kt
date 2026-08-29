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
     * 包装一次针对当前主要媒体的操作。
     *
     * @param actionBody 找到media变量后执行的JavaScript语句。
     * @return 完整自执行脚本，执行成功返回1，无媒体返回0。
     */
    private fun mediaActionScript(actionBody: String): String {
        return MEDIA_ACTION_TEMPLATE.replace(ACTION_PLACEHOLDER, actionBody)
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

        const val MEDIA_TOGGLE_BODY = "if (media.paused) { media.play(); } else { media.pause(); }"
        const val MEDIA_SEEK_BODY =
            "media.currentTime = Math.max(0, Math.min(isFinite(media.duration) ? media.duration : media.currentTime + (%d), media.currentTime + (%d)));"
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
                                media.defaultPlaybackRate = self.config.playbackRate;
                                if (Math.abs(media.playbackRate - self.config.playbackRate) > 0.001) {
                                    self.applyingRate = true;
                                    try { media.playbackRate = self.config.playbackRate; } catch (ignored) {}
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
                                        if (!self.applyingRate &&
                                            Math.abs(media.playbackRate - self.config.playbackRate) > 0.001) {
                                            try { media.playbackRate = self.config.playbackRate; } catch (ignored) {}
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
