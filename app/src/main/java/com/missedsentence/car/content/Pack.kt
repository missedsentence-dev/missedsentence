package com.missedsentence.car.content

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.json.JSONObject

/** 문장 하나. 녹음 파일이 없으면 [audio]가 null이고, 글도 없으면 재생할 수 없습니다. */
class Sentence(val index: Int, val text: String, val audio: String?, val ms: Long)

/**
 * 문장 단위로 나눈 콘텐츠 묶음. assets/packs/묶음이름/pack.json 과 문장별 소리 파일로 이루어집니다.
 * 어학 묶음은 다시 들을 문장부터 이어 듣고, 이야기 묶음은 정차 화면에서 앞뒤 문장과 함께 다시 듣습니다.
 */
class Pack(
    val id: String,
    val title: String,
    val kind: Kind,
    val language: String,
    val description: String,
    val voice: String,
    val order: Int,
    val sentences: List<Sentence>,
    private val assetDir: String
) {
    enum class Kind(val label: String) { LANGUAGE("어학"), STORY("이야기") }

    val isEmpty: Boolean get() = sentences.isEmpty()
    val audioCount: Int get() = sentences.count { it.audio != null }

    fun open(context: Context, sentence: Sentence): AssetFileDescriptor? {
        val name = sentence.audio ?: return null
        return try {
            context.assets.openFd("$assetDir/$name")
        } catch (t: Throwable) {
            null
        }
    }
}

object PackRepo {

    private const val ROOT = "packs"

    fun loadAll(context: Context): List<Pack> {
        val assets = context.assets
        val dirs = runCatching { assets.list(ROOT) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pack>()
        for (dir in dirs.sorted()) {
            val path = "$ROOT/$dir"
            val files = runCatching { assets.list(path) }.getOrNull()?.toSet() ?: continue
            if ("pack.json" !in files) continue
            val json = runCatching {
                assets.open("$path/pack.json").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            parse(json, dir, path, files)?.let { out.add(it) }
        }
        return out.sortedBy { it.order }
    }

    /** [files]에 실제로 들어 있는 소리 파일만 녹음으로 인정합니다. */
    fun parse(json: String, fallbackId: String, assetDir: String, files: Set<String>): Pack? = try {
        val o = JSONObject(json)
        val list = o.optJSONArray("sentences")
        val sentences = ArrayList<Sentence>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val audio = s.optString("audio", "").takeIf { it.isNotBlank() && it in files }
                val text = s.optString("text", "")
                if (audio == null && text.isBlank()) continue
                sentences.add(Sentence(sentences.size, text, audio, s.optLong("ms", 0L)))
            }
        }
        Pack(
            id = o.optString("id", fallbackId),
            title = o.optString("title", fallbackId),
            kind = if (o.optString("kind") == "story") Pack.Kind.STORY else Pack.Kind.LANGUAGE,
            language = o.optString("language", "ko"),
            description = o.optString("description", ""),
            voice = o.optString("voice", ""),
            order = o.optInt("order", 100),
            sentences = sentences,
            assetDir = assetDir
        )
    } catch (t: Throwable) {
        null
    }
}
