package wtf.evi.mobile.data.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.store.DownloadStore
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    val downloadStore: DownloadStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(3) // Up to 3 parallel downloads
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun downloadSong(song: Song, quality: AudioQuality = AudioQuality.HIGH) {
        if (downloadStore.isDownloaded(song.id)) return
        if (activeJobs.containsKey(song.id)) return

        val job = scope.launch {
            downloadSemaphore.withPermit {
                runDownload(song, quality)
            }
        }
        activeJobs[song.id] = job
        job.invokeOnCompletion { activeJobs.remove(song.id) }
    }

    fun downloadSongs(songs: List<Song>, quality: AudioQuality = AudioQuality.HIGH) {
        songs.forEach { song ->
            downloadSong(song, quality)
        }
    }

    fun cancelDownload(songId: String) {
        activeJobs.remove(songId)?.cancel()
        downloadStore.updateProgress(songId, null)
        downloadStore.getTempAudioFile(songId).delete()
    }

    fun deleteDownload(songId: String) {
        cancelDownload(songId)
        downloadStore.removeDownloadedSong(songId)
    }

    fun deleteDownloads(songs: List<Song>) {
        songs.forEach { deleteDownload(it.id) }
    }

    fun deleteAllDownloads() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        downloadStore.removeAllDownloads()
    }

    private suspend fun runDownload(song: Song, quality: AudioQuality) = withContext(Dispatchers.IO) {
        val streamUrl = song.resolvedStreamUrl(quality)
        val tempFile = downloadStore.getTempAudioFile(song.id)
        val finalAudioFile = downloadStore.getDownloadedAudioFile(song.id) ?: return@withContext

        try {
            downloadStore.updateProgress(song.id, 0.05f)

            // 1. Download stream audio
            val request = Request.Builder()
                .url(streamUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    downloadStore.updateProgress(song.id, null)
                    tempFile.delete()
                    return@withContext
                }

                val body = response.body ?: run {
                    downloadStore.updateProgress(song.id, null)
                    tempFile.delete()
                    return@withContext
                }

                val totalBytes = body.contentLength().coerceAtLeast(1L)
                var bytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            val progress = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.95f)
                            downloadStore.updateProgress(song.id, progress)
                        }
                        output.flush()
                    }
                }
            }

            // Verify integrity
            if (tempFile.exists() && tempFile.length() > 1024L) {
                if (finalAudioFile.exists()) finalAudioFile.delete()
                tempFile.renameTo(finalAudioFile)

                // 2. Download thumbnail
                val thumbUrl = song.resolvedCoverUrl(256)
                if (thumbUrl.isNotBlank()) {
                    runCatching {
                        val thumbReq = Request.Builder().url(thumbUrl).build()
                        httpClient.newCall(thumbReq).execute().use { tResp ->
                            if (tResp.isSuccessful) {
                                tResp.body?.byteStream()?.use { tIn ->
                                    downloadStore.getDownloadedThumbFile(song.id)?.let { tFile ->
                                        FileOutputStream(tFile).use { tOut ->
                                            tIn.copyTo(tOut)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                downloadStore.addDownloadedSong(song)
            } else {
                tempFile.delete()
                downloadStore.updateProgress(song.id, null)
            }
        } catch (e: Exception) {
            tempFile.delete()
            downloadStore.updateProgress(song.id, null)
        }
    }
}
