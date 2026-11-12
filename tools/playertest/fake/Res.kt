package android.content.res

import java.io.File
import java.io.FileDescriptor

/** 검사에서는 소리 길이(ms)를 length 자리에 실어 보냅니다. */
class AssetFileDescriptor(val length: Long) {
    val fileDescriptor = FileDescriptor()
    val startOffset = 0L
    fun close() {}
}

class AssetManager {
    /** 경로 → 소리 길이(ms). 재생기 검사에서 씁니다. */
    val durations = HashMap<String, Long>()

    /** 실제 assets 폴더. 정해 두면 그 안의 파일을 읽습니다. */
    var root: File? = null

    /** [root]의 소리 파일에 줄 길이(ms) */
    var defaultDuration = 3_000L

    fun openFd(path: String): AssetFileDescriptor {
        durations[path]?.let { return AssetFileDescriptor(it) }
        val r = root
        if (r != null && File(r, path).isFile) return AssetFileDescriptor(defaultDuration)
        throw java.io.FileNotFoundException(path)
    }

    fun list(path: String): Array<String>? = root?.let { File(it, path).list() }

    fun open(path: String): java.io.InputStream {
        val r = root ?: throw java.io.FileNotFoundException(path)
        return File(r, path).inputStream()
    }
}
