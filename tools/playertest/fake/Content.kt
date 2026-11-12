package android.content

import android.content.res.AssetManager
import android.media.AudioManager

open class Context {
    val audio = AudioManager()
    val assets = AssetManager()
    val applicationContext: Context get() = this
    private val prefs = HashMap<String, SharedPreferences>()

    fun getSystemService(name: String): Any? = if (name == AUDIO_SERVICE) audio else null

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { SharedPreferences() }

    companion object {
        const val AUDIO_SERVICE = "audio"
        const val MODE_PRIVATE = 0
    }
}

/** 메모리에만 두는 검사용 저장소 */
class SharedPreferences {
    val map = HashMap<String, Any?>()

    inner class Editor {
        fun putInt(k: String, v: Int): Editor { map[k] = v; return this }
        fun putString(k: String, v: String?): Editor { map[k] = v; return this }
        fun putBoolean(k: String, v: Boolean): Editor { map[k] = v; return this }
        fun apply() {}
    }

    fun edit() = Editor()
    fun getInt(k: String, d: Int): Int = map[k] as? Int ?: d
    fun getString(k: String, d: String?): String? = map[k] as? String ?: d
    fun getBoolean(k: String, d: Boolean): Boolean = map[k] as? Boolean ?: d
}
