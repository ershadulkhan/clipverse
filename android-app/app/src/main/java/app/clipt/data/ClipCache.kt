package app.clipt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Last-known clip list on disk so the app paints instantly, even offline. */
class ClipCache(context: Context) {
    private val file = File(context.filesDir, "clips.json")

    suspend fun read(): List<Clip> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { Prefs.json.decodeFromString(ListSerializer(Clip.serializer()), file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun write(clips: List<Clip>) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(Prefs.json.encodeToString(ListSerializer(Clip.serializer()), clips.take(60))) }
    }

    fun clear() { file.delete() }
}
