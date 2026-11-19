package com.ipvio.stream

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * PROTOTYPE: reads the real tracks of a stream (resolution, HDR/Dolby Vision, audio format, languages) with Media3's
 * MetadataRetriever, i.e. only the container header is loaded, nothing is decoded. Xtream's get_vod_info does not
 * deliver these values at this provider (video/audio empty), so this is the on-device counterpart to ffprobe.
 *
 * Results are kept in memory per URL for the app session (errors too, so a broken stream is not retried on every visit).
 */
object MediaProbe {
    private const val TAG = "MediaProbe"
    private const val TIMEOUT_S = 20L

    data class Result(
        val video: String?,
        val hdr: String?,
        val audio: List<String>,
        val subs: List<String>,
        val ms: Long,
        val error: String? = null,
    )

    private val cache = ConcurrentHashMap<String, Result>()

    fun cached(url: String): Result? = cache[url]

    suspend fun probe(ctx: Context, url: String): Result = cache[url] ?: withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = try {
            // Same HTTP settings as the player (redirects http -> https, timeouts)
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
            val msf = DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http))
            MetadataRetriever.Builder(ctx.applicationContext, MediaItem.fromUri(url))
                .setMediaSourceFactory(msf)
                .build()
                .use { mr ->
                    val groups = mr.retrieveTrackGroups().get(TIMEOUT_S, TimeUnit.SECONDS)
                    val formats = (0 until groups.length).flatMap { g -> groups[g].let { tg -> (0 until tg.length).map { tg.getFormat(it) } } }
                    describe(formats, System.currentTimeMillis() - t0)
                }
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            Result(null, null, emptyList(), emptyList(), System.currentTimeMillis() - t0,
                error = "${cause.javaClass.simpleName}: ${cause.message ?: ""}".take(160))
        }
        Log.i(TAG, "${r.ms} ms video=${r.video} hdr=${r.hdr} audio=${r.audio} subs=${r.subs} err=${r.error}")
        cache[url] = r
        r
    }

    private fun describe(formats: List<Format>, ms: Long): Result {
        val videos = formats.filter { MimeTypes.isVideo(it.sampleMimeType) }
        val v = videos.maxByOrNull { it.width.toLong() * it.height }
        val video = v?.let { f ->
            val res = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else "?"
            "$res ${videoCodec(f)}"
        }
        // HDR: transfer function of the main track; Dolby Vision may sit on any video track (mime or codecs string)
        val transfer = when (v?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val dv = videos.firstNotNullOfOrNull { dvProfile(it) }
        val hdr = listOfNotNull(transfer ?: if (v != null) "SDR" else null, dv?.let { "Dolby Vision P$it" }).joinToString(" + ").ifEmpty { null }
        val audio = formats.filter { MimeTypes.isAudio(it.sampleMimeType) }.map { f ->
            listOfNotNull(f.language ?: "?", audioCodec(f), channels(f.channelCount)).joinToString(" ")
        }
        val subs = formats.filter { MimeTypes.isText(it.sampleMimeType) || it.sampleMimeType == MimeTypes.APPLICATION_SUBRIP }
            .map { it.language ?: "?" }
        return Result(video, hdr, audio, subs, ms)
    }

    private fun videoCodec(f: Format): String = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "h264"
        MimeTypes.VIDEO_H265 -> "hevc"
        MimeTypes.VIDEO_AV1 -> "av1"
        MimeTypes.VIDEO_VP9 -> "vp9"
        MimeTypes.VIDEO_DOLBY_VISION -> "dolby-vision"
        MimeTypes.VIDEO_MPEG2 -> "mpeg2"
        else -> f.sampleMimeType?.substringAfter('/') ?: "?"
    }

    /** Dolby Vision profile from codecs like "dvhe.08.06" / "dvh1.05.06", null if none. */
    internal fun dvProfile(f: Format): Int? {
        val c = f.codecs?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") }
        if (c != null) return c.split('.').getOrNull(1)?.toIntOrNull() ?: 0
        return if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) 0 else null
    }

    private fun audioCodec(f: Format): String = when (f.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_E_AC3 -> "eac3"
        MimeTypes.AUDIO_E_AC3_JOC -> "eac3 Atmos"
        MimeTypes.AUDIO_AC4 -> "ac4"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        MimeTypes.AUDIO_DTS -> "dts"
        MimeTypes.AUDIO_DTS_HD -> "dts-hd"
        MimeTypes.AUDIO_DTS_EXPRESS -> "dts-express"
        MimeTypes.AUDIO_DTS_UHD_P2 -> "dts:x"
        MimeTypes.AUDIO_MPEG -> "mp3"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_FLAC -> "flac"
        else -> f.sampleMimeType?.substringAfter('/') ?: "?"
    }

    private fun channels(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "mono"
        n == 2 -> "2.0"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "${n}ch"
    }
}

/** PROTOTYPE: one line on the detail page with the probed tracks (spinner text while loading, error text on failure). */
@androidx.compose.runtime.Composable
fun ProbeLine(url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val r by androidx.compose.runtime.produceState(MediaProbe.cached(url), url) {
        if (value == null) value = MediaProbe.probe(ctx, url)
    }
    val res = r
    val text = when {
        res == null -> "Datei wird geprüft …"
        res.error != null -> "Prüfung fehlgeschlagen (${res.ms} ms): ${res.error}"
        else -> listOfNotNull(
            res.video, res.hdr,
            res.audio.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Ton: "),
            res.subs.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "UT: "),
            "${res.ms} ms",
        ).joinToString("  ·  ")
    }
    Text(raw = true, text = text, color = Muted, fontSize = spx(15), maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
