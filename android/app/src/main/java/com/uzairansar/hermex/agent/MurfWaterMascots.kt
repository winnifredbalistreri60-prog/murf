package com.uzairansar.hermex.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * «Водяные» коротышки 1.9.0: квадратные 288×288 клипы (H.264 без звука, бесшовная петля ~6 с, ~150–220 КБ)
 * и статичный кадр WebP в assets/mascots. Имена файлов совпадают с порядком [MurfHat].
 */
object MurfWaterMascots {
    val ids = listOf("putnik", "master", "zvezdochet", "hudozhnik", "romashka", "mehanik", "frant", "lyzhnik", "povaryonok", "rybak")
    fun id(hat: MurfHat): String = ids[hat.ordinal]
    fun stillAsset(hat: MurfHat) = "mascots/murf_water_${id(hat)}.webp"
    fun clipAsset(hat: MurfHat) = "mascots/murf_water_${id(hat)}.mp4"

    private val cache = LruCache<String, Bitmap>(12)
    fun still(context: Context, hat: MurfHat): Bitmap? {
        val key = stillAsset(hat)
        cache.get(key)?.let { return it }
        return runCatching { context.assets.open(key).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { cache.put(key, it) }
    }
}

/** Картинка коротышки; [play] — крутить зацикленное видео без звука поверх статичного кадра. */
@Composable
fun MurfWaterMascot(hat: MurfHat, play: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember(hat) { MurfWaterMascots.still(context, hat) }
    Box(modifier) {
        still?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (play) MurfWaterClip(MurfWaterMascots.clipAsset(hat), Modifier.fillMaxSize())
    }
}

@Composable
private fun MurfWaterClip(asset: String, modifier: Modifier) {
    val holder = remember(asset) { ClipHolder() }
    DisposableEffect(asset) { onDispose { holder.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = holder.start(ctx, asset, Surface(st))
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { holder.release(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

private class ClipHolder {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    fun start(context: Context, asset: String, s: Surface) {
        release()
        surface = s
        runCatching {
            val mp = MediaPlayer()
            context.assets.openFd(asset).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, what, extra -> Log.w("MURF-mascot", "clip error $what/$extra $asset"); true }
            mp.prepareAsync()
            player = mp
        }.onFailure { Log.w("MURF-mascot", "clip start failed $asset: $it"); release() }
    }

    fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        surface?.release()
        surface = null
    }
}
