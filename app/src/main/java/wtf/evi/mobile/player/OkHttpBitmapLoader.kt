package wtf.evi.mobile.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class OkHttpBitmapLoader(
    private val context: Context,
    private val httpClient: OkHttpClient
) : BitmapLoader {
    private val appContext: Context = context.applicationContext
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executor.submit<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IOException("Failed to decode bitmap bytes")
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executor.submit<Bitmap> {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path ?: throw IOException("Missing file path for artwork URI")
                    val file = File(path)
                    if (!file.exists() || file.length() <= 0L) {
                        throw IOException("Artwork file missing or empty")
                    }
                    BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IOException("Failed to decode local artwork bitmap")
                }
                "content" -> {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: throw IOException("Failed to decode content artwork URI")
                }
                else -> {
                    val req = Request.Builder().url(uri.toString()).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} loading artwork")
                        val bytes = resp.body?.bytes() ?: throw IOException("Empty artwork body")
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: throw IOException("Failed to decode artwork bitmap")
                    }
                }
            }
        }
    }
}
