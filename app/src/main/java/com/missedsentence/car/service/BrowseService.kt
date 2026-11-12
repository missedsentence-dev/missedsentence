package com.missedsentence.car.service

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.service.media.MediaBrowserService
import com.missedsentence.car.MissedApp
import com.missedsentence.car.content.Pack
import com.missedsentence.car.player.SentencePlayer

/**
 * 차량 미디어 화면이 묶음 목록을 읽고 재생을 맡길 수 있게 여는 창구.
 * Pleos Connect의 음악과 오디오 앱은 MediaBrowserService로 콘텐츠와 재생 상태를 차량 시스템에 알려야 합니다.
 * 주행 중에는 앱 화면 대신 차량 미디어 화면에 제목, 쉬는 이유, 재생과 이전 버튼이 보입니다.
 * 목록은 오디오북과 어학으로 나누어 줍니다.
 */
class BrowseService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        runCatching { sessionToken = MissedApp.controller(this).player.sessionToken }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val packs = MissedApp.controller(this).packs.filter { !it.isEmpty }
        val items = ArrayList<MediaBrowser.MediaItem>()
        when (parentId) {
            ROOT -> for (kind in Pack.Kind.values()) {
                val n = packs.count { it.kind == kind }
                if (n == 0) continue
                items.add(
                    MediaBrowser.MediaItem(
                        MediaDescription.Builder()
                            .setMediaId(KIND_PREFIX + kind.name)
                            .setTitle(shelfTitle(kind))
                            .setSubtitle("묶음 ${n}개")
                            .build(),
                        MediaBrowser.MediaItem.FLAG_BROWSABLE
                    )
                )
            }
            else -> {
                val kind = Pack.Kind.values().firstOrNull { KIND_PREFIX + it.name == parentId }
                for (p in packs) {
                    if (p.kind != kind) continue
                    items.add(
                        MediaBrowser.MediaItem(
                            MediaDescription.Builder()
                                .setMediaId(p.id)
                                .setTitle(p.title)
                                .setSubtitle("문장 ${p.sentences.size}개")
                                .setIconUri(Uri.parse(SentencePlayer.ART_URI))
                                .build(),
                            MediaBrowser.MediaItem.FLAG_PLAYABLE
                        )
                    )
                }
            }
        }
        result.sendResult(items)
    }

    private fun shelfTitle(kind: Pack.Kind): String = when (kind) {
        Pack.Kind.STORY -> "오디오북"
        Pack.Kind.LANGUAGE -> "어학"
    }

    companion object {
        private const val ROOT = "root"
        private const val KIND_PREFIX = "kind:"
    }
}
