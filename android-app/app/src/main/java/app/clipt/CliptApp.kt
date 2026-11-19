package app.clipt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import app.clipt.data.Http
import app.clipt.data.Prefs

/**
 * Application does no work at start-up: no Firebase auto-init, no WorkManager periodic
 * jobs, no listeners. Everything below is lazy.
 */
class CliptApp : Application(), SingletonImageLoader.Factory {

    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CLIPS, getString(R.string.channel_clips), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Thumbnails are cached on disk, so scrolling history never re-downloads an image. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("clip_images"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    companion object {
        const val CHANNEL_CLIPS = "clips"
    }
}

val Context.prefs: Prefs get() = (applicationContext as CliptApp).prefs
