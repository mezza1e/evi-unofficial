package wtf.evi.mobile.data.store

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import wtf.evi.mobile.data.model.Song
import java.io.File

class DownloadStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadsDir = File(context.filesDir, "offline_music").apply { mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val _downloadedSongs = MutableStateFlow<List<Song>>(loadSongs())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()

    private val _downloadedSongIds = MutableStateFlow<Set<String>>(_downloadedSongs.value.map { it.id }.toSet())
    val downloadedSongIds: StateFlow<Set<String>> = _downloadedSongIds.asStateFlow()

    // Map of songId -> progress (0.0 to 1.0)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private fun loadSongs(): List<Song> {
        val raw = prefs.getString(KEY_DOWNLOADED_SONGS, null) ?: return emptyList()
        val list = runCatching { json.decodeFromString<List<Song>>(raw) }.getOrDefault(emptyList())
        // Verify files actually exist on disk
        return list.filter { song ->
            val audioFile = getDownloadedAudioFile(song.id)
            audioFile != null && audioFile.exists() && audioFile.length() > 0L
        }
    }

    @Synchronized
    private fun persistSongs(songs: List<Song>) {
        _downloadedSongs.value = songs
        _downloadedSongIds.value = songs.map { it.id }.toSet()
        prefs.edit().putString(KEY_DOWNLOADED_SONGS, json.encodeToString(songs)).apply()
    }

    fun isDownloaded(songId: String): Boolean {
        if (songId.isBlank()) return false
        val file = getDownloadedAudioFile(songId)
        return file != null && file.exists() && file.length() > 0L
    }

    fun getDownloadedAudioFile(songId: String): File? {
        if (songId.isBlank()) return null
        return File(downloadsDir, "$songId.audio")
    }

    fun getDownloadedThumbFile(songId: String): File? {
        if (songId.isBlank()) return null
        return File(downloadsDir, "$songId.thumb")
    }

    fun getTempAudioFile(songId: String): File {
        return File(downloadsDir, "$songId.tmp")
    }

    fun updateProgress(songId: String, progress: Float?) {
        val cur = _downloadProgress.value.toMutableMap()
        if (progress == null || progress >= 1f) {
            cur.remove(songId)
        } else {
            cur[songId] = progress.coerceIn(0f, 1f)
        }
        _downloadProgress.value = cur
    }

    @Synchronized
    fun addDownloadedSong(song: Song) {
        val current = _downloadedSongs.value.toMutableList()
        current.removeAll { it.id == song.id }
        current.add(0, song)
        persistSongs(current)
        updateProgress(song.id, null)
    }

    @Synchronized
    fun removeDownloadedSong(songId: String) {
        val current = _downloadedSongs.value.filterNot { it.id == songId }
        persistSongs(current)
        updateProgress(songId, null)
        runCatching { getDownloadedAudioFile(songId)?.delete() }
        runCatching { getDownloadedThumbFile(songId)?.delete() }
        runCatching { getTempAudioFile(songId).delete() }
    }

    @Synchronized
    fun removeAllDownloads() {
        persistSongs(emptyList())
        _downloadProgress.value = emptyMap()
        runCatching {
            downloadsDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun getTotalDownloadedBytes(): Long {
        return runCatching {
            downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val PREFS_NAME = "evi_downloads"
        private const val KEY_DOWNLOADED_SONGS = "downloaded_songs_list"
    }
}
