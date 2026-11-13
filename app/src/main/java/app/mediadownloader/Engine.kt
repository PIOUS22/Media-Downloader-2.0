package app.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface DownloadListener {
    fun progress(percent: Float, headline: String, detail: String)
    fun status(text: String)
}

data class DownloadResult(val savedCount: Int, val hadErrors: Boolean, val cancelled: Boolean, val error: String?)

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** Thin wrapper around yt-dlp (youtubedl-android) that mirrors the desktop app's logic. */
class Engine(private val ctx: Context) {

    companion object {
        const val PROCESS_ID = "md-download"
        const val SAVE_FOLDER = "MediaDownloader"
    }

    @Volatile
    private var cancelRequested = false

    fun init() {
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
    }

    // ---------------------------------------------------------------- analyse

    private fun json(url: String, cookies: String?, vararg flags: String): JSONObject {
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", 25)
        flags.forEach { req.addOption(it) }
        if (cookies != null) req.addOption("--cookies", cookies)
        val out = YoutubeDL.getInstance().execute(req).out
        val i = out.indexOf('{')
        if (i < 0) throw IllegalStateException("No information could be extracted from this link.")
        return JSONObject(out.substring(i))
    }

    private fun entryList(info: JSONObject, yt: Boolean): List<Entry> {
        val arr = info.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<Entry>()
        var i = 0
        for (k in 0 until arr.length()) {
            i++
            val e = arr.optJSONObject(k) ?: continue
            var url = e.str("url") ?: e.str("webpage_url") ?: ""
            val id = e.str("id")
            if (!url.startsWith("http") && id != null && yt) url = "https://www.youtube.com/watch?v=$id"
            out += Entry(
                index = i,
                id = id,
                title = e.str("title") ?: "Video $i",
                duration = if (e.isNull("duration")) null else e.optDouble("duration").toLong(),
                url = if (url.startsWith("http")) url else "",
            )
        }
        return out
    }

    private fun thumbOf(o: JSONObject): String? {
        o.str("thumbnail")?.let { if (it.startsWith("http")) return it }
        val t = o.optJSONArray("thumbnails")
        if (t != null) {
            for (i in t.length() - 1 downTo 0) {
                val u = t.optJSONObject(i)?.str("url")
                if (u != null && u.startsWith("http")) return u
            }
        }
        return null
    }

    fun analyze(url: String, cookies: String?): Analysis {
        val yt = Urls.isYoutube(url)
        val (listId, videoId) = if (yt) Urls.youtubeIds(url) else (null to null)

        var kind = "video"
        var playlistUrl: String? = null
        var playlistTitle: String? = null
        var playlistCount: Int? = null
        var entries: List<Entry> = emptyList()
        val info: JSONObject

        if (yt && videoId != null) {
            info = json(url, cookies, "--no-playlist")
            if (listId != null) {                       // video inside a playlist -> offer both
                val purl = "https://www.youtube.com/playlist?list=$listId"
                runCatching { json(purl, cookies, "--flat-playlist") }.onSuccess { p ->
                    entries = entryList(p, yt)
                    playlistUrl = purl
                    playlistTitle = p.str("title")
                    playlistCount = if (p.has("playlist_count") && !p.isNull("playlist_count"))
                        p.optInt("playlist_count") else entries.size.takeIf { it > 0 }
                }                                        // private / auto-generated mixes: ignore
            }
        } else {
            info = json(url, cookies, "--flat-playlist")
            if (info.optString("_type") == "playlist") {
                kind = "playlist"
                entries = entryList(info, yt)
                playlistUrl = if (yt && listId != null) "https://www.youtube.com/playlist?list=$listId" else url
                playlistTitle = info.str("title")
                playlistCount = if (info.has("playlist_count") && !info.isNull("playlist_count"))
                    info.optInt("playlist_count") else entries.size.takeIf { it > 0 }
            }
        }

        val extractor = info.str("extractor_key") ?: ""
        val (platform, color) = Urls.platform(url, extractor)
        var thumb = thumbOf(info)
        if (thumb == null) thumb = info.optJSONArray("entries")?.objects()?.firstNotNullOfOrNull { thumbOf(it) }

        return Analysis(
            url = url,
            isYoutube = yt,
            kind = kind,
            playlistUrl = playlistUrl,
            playlistTitle = playlistTitle,
            playlistCount = playlistCount,
            entries = entries,
            title = info.str("title") ?: playlistTitle ?: "Untitled",
            uploader = info.str("uploader") ?: info.str("channel") ?: info.str("uploader_id") ?: "",
            duration = if (info.isNull("duration")) null else info.optDouble("duration").toLong().takeIf { it > 0 },
            thumb = thumb,
            platform = platform,
            platformColor = color,
        )
    }

    // --------------------------------------------------------------- download

    private fun buildRequest(
        url: String, outDir: File, preset: Preset, isPlaylist: Boolean,
        items: List<Int>?, subs: SubOpts?, cookies: String?,
    ): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        val tmpl = if (isPlaylist) "%(playlist_title)s/%(playlist_index)03d - %(title)s.%(ext)s"
        else "%(title)s.%(ext)s"
        r.addOption("-o", File(outDir, tmpl).absolutePath)
        r.addOption("--newline")
        r.addOption("--no-mtime")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", 25)
        r.addOption("--retries", 5)
        r.addOption("--fragment-retries", 5)
        r.addOption("--trim-filenames", 160)
        r.addOption("--embed-metadata")
        if (isPlaylist) {
            r.addOption("--yes-playlist")
            r.addOption("--ignore-errors")
        } else {
            r.addOption("--no-playlist")
        }
        if (!items.isNullOrEmpty()) r.addOption("--playlist-items", items.joinToString(","))
        if (cookies != null) r.addOption("--cookies", cookies)

        if (preset.isAudio) {
            val codec = preset.audio!!
            r.addOption("-f", if (codec == "m4a") "ba[ext=m4a]/ba/b" else "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", codec)
            r.addOption("--audio-quality", preset.quality ?: "0")
        } else {
            val h = preset.height
            r.addOption("-f", if (h != null) "bv*[height<=?$h]+ba/b[height<=?$h]" else "bv*+ba/b")
            r.addOption("-S", "res,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
        }

        if (subs != null) {
            r.addOption("--write-subs")
            if (subs.auto) r.addOption("--write-auto-subs")
            r.addOption("--sub-langs", if (subs.lang == "all") "all,-live_chat" else subs.lang)
            r.addOption("--convert-subs", "srt")
            if (!preset.isAudio && (subs.mode == "embed" || subs.mode == "both")) {
                r.addOption("--embed-subs")
                if (subs.mode == "both") r.addOption("-k")
            }
        }
        return r
    }

    fun cancel() {
        cancelRequested = true
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    fun download(
        url: String, preset: Preset, isPlaylist: Boolean, items: List<Int>?,
        subs: SubOpts?, cookies: String?, saveTree: Uri?, listener: DownloadListener,
    ): DownloadResult {
        cancelRequested = false
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val tmp = File(base, "dl-${System.currentTimeMillis()}").also { it.mkdirs() }

        val req = buildRequest(url, tmp, preset, isPlaylist, items, subs, cookies)

        // progress bookkeeping (same idea as the desktop app: item x stream)
        var ord = 0
        var total: Int? = items?.size
        var streamIdx = 0
        var nStreams = if (preset.isAudio) 1 else 2
        var title = "media"
        var lastDest: String? = null
        val pct = Regex("\\[download\\]\\s+([\\d.]+)%")
        val itemRe = Regex("Downloading item (\\d+) of (\\d+)")
        var lastEmit = 0L

        var error: String? = null
        try {
            YoutubeDL.getInstance().execute(req, PROCESS_ID) { _, _, line ->
                when {
                    itemRe.containsMatchIn(line) -> {
                        val m = itemRe.find(line)!!
                        ord = m.groupValues[1].toInt()
                        total = m.groupValues[2].toInt()
                        streamIdx = 0
                        lastDest = null
                    }
                    line.contains("format(s):") && line.contains("Downloading") -> {
                        val f = line.substringAfter("format(s):").trim()
                        nStreams = if (preset.isAudio) 1 else f.split("+").size.coerceAtLeast(1)
                    }
                    line.startsWith("[download] Destination:") -> {
                        val f = line.substringAfter("Destination:").trim()
                        if (lastDest != null) streamIdx++
                        lastDest = f
                        title = File(f).nameWithoutExtension.replace(Regex("\\.f\\d+$"), "")
                    }
                    line.startsWith("[Merger]") -> listener.status("Merging video and audio…")
                    line.startsWith("[ExtractAudio]") -> listener.status("Converting audio…")
                    line.startsWith("[EmbedSubtitle]") || line.startsWith("[Metadata]") ||
                        line.startsWith("[SubtitlesConvertor]") -> listener.status("Finalizing…")
                }
                val m = pct.find(line)
                if (m != null) {
                    val frac = (m.groupValues[1].toFloatOrNull() ?: 0f) / 100f
                    val item = ((minOf(streamIdx, nStreams - 1)) + frac) / nStreams
                    val t = total
                    val overall = if (isPlaylist && t != null && t > 0 && ord > 0)
                        ((ord - 1) + item) / t else item
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 120 || frac >= 1f) {
                        lastEmit = now
                        val head = if (isPlaylist && t != null && ord > 0) "Downloading $ord of $t: $title"
                        else "Downloading: $title"
                        val detail = line.substringAfter("[download]").trim().replace(Regex("\\s+"), " ")
                        listener.progress(minOf(99.9f, overall * 100f), head, detail)
                    }
                }
            }
        } catch (e: Throwable) {
            if (!cancelRequested) error = cleanError(e)
        }

        if (cancelRequested) {
            tmp.deleteRecursively()
            return DownloadResult(0, false, true, null)
        }

        listener.status("Saving to Downloads…")
        val saved = runCatching { saveAll(tmp, saveTree) }.getOrElse { e ->
            tmp.deleteRecursively()
            return DownloadResult(0, false, false, "Could not save the file: ${e.message}")
        }
        tmp.deleteRecursively()

        // A playlist with --ignore-errors can fail partially: keep what worked.
        return if (saved == 0) DownloadResult(0, false, false, error ?: "The download failed.")
        else DownloadResult(saved, error != null, false, error)
    }

    // ------------------------------------------------- copy into Downloads/

    private fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "srt" -> "application/x-subrip"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun saveToTree(tmp: File, files: List<File>, tree: Uri): Int {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Chosen folder is not available")
        if (!root.canWrite()) throw IllegalStateException("No permission for the chosen folder - pick it again")
        var n = 0
        for (f in files) {
            var dir = root
            f.relativeTo(tmp).parent?.split(File.separatorChar)?.filter { it.isNotEmpty() }?.forEach { part ->
                dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part)
                    ?: throw IllegalStateException("Cannot create folder $part")
            }
            val out = dir.createFile(mimeOf(f), f.name) ?: throw IllegalStateException("Cannot create ${f.name}")
            ctx.contentResolver.openOutputStream(out.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            n++
        }
        return n
    }

    private fun saveAll(tmp: File, saveTree: Uri?): Int {
        val skip = listOf(".part", ".ytdl", ".temp", ".tmp")
        var n = 0
        val files = tmp.walkTopDown().filter { it.isFile && skip.none { s -> it.name.endsWith(s) } }.toList()
        if (saveTree != null) return saveToTree(tmp, files, saveTree)
        for (f in files) {
            val rel = f.relativeTo(tmp).parent
            val sub = listOfNotNull(SAVE_FOLDER, rel).joinToString("/")
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(f))
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
                dir.mkdirs()
                f.copyTo(File(dir, f.name), overwrite = true)
            }
            n++
        }
        return n
    }
}
