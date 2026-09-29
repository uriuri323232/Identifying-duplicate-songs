@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.duplicatesongs

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

// ---------- Data model ----------

data class Song(
    val id: Long,
    val title: String,
    val path: String,
    val durationSec: Long,
    val sizeBytes: Long,
    val norm: String
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Rough bitrate estimate (file size / duration), in kbps. 0 if the duration is unknown. */
    val bitrateKbps: Long
        get() = if (durationSec > 0) sizeBytes * 8 / durationSec / 1000 else 0L

    /** The folder that contains the file. */
    val folder: String
        get() = path.substringBeforeLast('/', "")

    /**
     * Stable identity used to remember "these are NOT duplicates" across scans.
     * It survives MediaStore id changes and moving the file to another folder.
     */
    val fingerprint: String = "$title|$sizeBytes|$durationSec"
}

// ---------- "Not duplicates" memory (saved on the device) ----------

fun canonPair(a: String, b: String): Pair<String, String> = if (a <= b) Pair(a, b) else Pair(b, a)

/** Human readable file name out of a fingerprint ("name|size|duration"). */
fun fingerprintName(fp: String): String = fp.substringBeforeLast('|').substringBeforeLast('|')

class NotDuplicateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("not_duplicates", Context.MODE_PRIVATE)
    private val pairs = LinkedHashSet<Pair<String, String>>()

    init {
        try {
            val arr = JSONArray(prefs.getString("pairs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pairs.add(Pair(o.getString("a"), o.getString("b")))
            }
        } catch (_: Exception) {
            // Corrupt data: start fresh rather than crash.
        }
    }

    private fun save() {
        val arr = JSONArray()
        for ((a, b) in pairs) arr.put(JSONObject().put("a", a).put("b", b))
        prefs.edit().putString("pairs", arr.toString()).apply()
    }

    fun snapshot(): Set<Pair<String, String>> = HashSet(pairs)
    fun asList(): List<Pair<String, String>> = pairs.toList()

    /** Marks every pair among the given songs as "not duplicates". */
    fun addAll(fingerprints: List<String>) {
        val f = fingerprints.distinct()
        for (i in f.indices) for (j in i + 1 until f.size) pairs.add(canonPair(f[i], f[j]))
        save()
    }

    fun remove(pair: Pair<String, String>) { pairs.remove(pair); save() }
    fun clear() { pairs.clear(); save() }
}

// ---------- Saved settings & cleaning statistics ----------

class AppPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sim: Float
        get() = p.getFloat("sim", 0.8f)
        set(v) { p.edit().putFloat("sim", v).apply() }

    var tol: Float
        get() = p.getFloat("tol", 3f)
        set(v) { p.edit().putFloat("tol", v).apply() }

    var useTrash: Boolean
        get() = p.getBoolean("use_trash", true)
        set(v) { p.edit().putBoolean("use_trash", v).apply() }

    var freedBytes: Long
        get() = p.getLong("freed_bytes", 0L)
        set(v) { p.edit().putLong("freed_bytes", v).apply() }

    var freedCount: Int
        get() = p.getInt("freed_count", 0)
        set(v) { p.edit().putInt("freed_count", v).apply() }
}

// ---------- Text normalization & similarity ----------

private val NOISE_WORDS = listOf(
    "official", "video", "audio", "lyrics", "lyric", "remix", "remaster",
    "remastered", "version", "radio edit", "clean", "explicit", "ft",
    "feat", "featuring", "hd", "hq", "live", "mv", "full"
)

fun normalizeTitle(raw: String): String {
    var s = raw.substringBeforeLast('.', raw) // drop extension if present
    s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    s = s.lowercase()
    // Braces are escaped because Android's regex engine treats an unescaped { or }
    // as a quantifier and throws a syntax error.
    s = s.replace(Regex("\\([^)]*\\)"), " ")   // ( ... )
    s = s.replace(Regex("\\[[^\\]]*\\]"), " ")  // [ ... ]
    s = s.replace(Regex("\\{[^}]*\\}"), " ")    // { ... }
    for (w in NOISE_WORDS) {
        s = s.replace(Regex("\\b" + Regex.escape(w) + "\\b"), " ")
    }
    s = s.replace(Regex("[^a-z0-9\\u0590-\\u05ff]+"), " ").trim().replace(Regex("\\s+"), " ")
    return s
}

fun levenshtein(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    if (m == 0) return n
    if (n == 0) return m
    var prev = IntArray(n + 1) { it }
    for (i in 1..m) {
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
            cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
            else 1 + minOf(prev[j - 1], prev[j], cur[j - 1])
        }
        prev = cur
    }
    return prev[n]
}

fun similarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val dist = levenshtein(a, b)
    return 1.0 - dist.toDouble() / max(max(a.length, b.length), 1)
}

// ---------- Union-Find for transitive grouping ----------

class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}

// ---------- MediaStore query ----------

fun loadSongs(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val path = cursor.getString(dataCol) ?: name
            val durMs = cursor.getLong(durCol)
            val size = cursor.getLong(sizeCol)
            songs.add(Song(id, name, path, durMs / 1000, size, normalizeTitle(name)))
        }
    }
    return songs
}

// ---------- Trash (Android 11+ MediaStore trash) ----------

/** A song that was moved to the system trash and can still be restored. */
data class TrashedSong(
    val id: Long,
    val title: String,
    val folder: String,
    val durationSec: Long,
    val sizeBytes: Long,
    /** When the system will delete it automatically (epoch seconds), 0 if unknown. */
    val expiresSec: Long
) {
    /** Days left before automatic deletion, or -1 if unknown. */
    fun daysLeft(): Long =
        if (expiresSec <= 0) -1L
        else ((expiresSec * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
}

private enum class TrashAction { Restore, DeleteForever }

/** Audio files that are currently in the system trash. Empty below Android 11. */
fun loadTrashedSongs(context: Context): List<TrashedSong> {
    if (Build.VERSION.SDK_INT >= 30) {
        val result = mutableListOf<TrashedSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.MediaColumns.DATE_EXPIRES
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.IS_MUSIC} != 0")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val expCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            while (c.moveToNext()) {
                val rawName = c.getString(nameCol) ?: continue
                // Trashed files are renamed by the system to ".trashed-<timestamp>-<name>".
                val name = rawName.replace(Regex("^\\.trashed-\\d+-"), "")
                val path = c.getString(dataCol) ?: ""
                result.add(
                    TrashedSong(
                        id = c.getLong(idCol),
                        title = name,
                        folder = path.substringBeforeLast('/', ""),
                        durationSec = c.getLong(durCol) / 1000,
                        sizeBytes = c.getLong(sizeCol),
                        expiresSec = if (c.isNull(expCol)) 0L else c.getLong(expCol)
                    )
                )
            }
        }
        return result.sortedByDescending { it.expiresSec }
    }
    return emptyList()
}

fun wastedBytes(group: List<Song>): Long =
    group.sumOf { it.sizeBytes } - (group.maxOfOrNull { it.sizeBytes } ?: 0L)

/**
 * Groups similar songs. Pairs listed in [notDuplicates] are never treated as duplicates,
 * even if a third song links them transitively (such groups are split).
 */
fun findDuplicateGroups(
    songs: List<Song>,
    simThreshold: Double,
    durationToleranceSec: Long,
    notDuplicates: Set<Pair<String, String>> = emptySet(),
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): List<List<Song>> {
    val n = songs.size
    val uf = UnionFind(n)
    val adj = HashMap<Int, MutableSet<Int>>()
    // Compare only songs with near durations: sort by duration and slide a window,
    // so we avoid the full O(n^2) comparison on large libraries.
    val order = songs.indices.sortedBy { songs[it].durationSec }
    for (p in order.indices) {
        val i = order[p]
        for (q in p + 1 until order.size) {
            val j = order[q]
            if (songs[j].durationSec - songs[i].durationSec > durationToleranceSec) break
            if (similarity(songs[i].norm, songs[j].norm) < simThreshold) continue
            if (notDuplicates.isNotEmpty() &&
                notDuplicates.contains(canonPair(songs[i].fingerprint, songs[j].fingerprint))
            ) continue
            uf.union(i, j)
            adj.getOrPut(i) { mutableSetOf() }.add(j)
            adj.getOrPut(j) { mutableSetOf() }.add(i)
        }
        onProgress(p + 1, order.size)
    }

    val components = LinkedHashMap<Int, MutableList<Int>>()
    for (idx in 0 until n) components.getOrPut(uf.find(idx)) { mutableListOf() }.add(idx)

    val result = ArrayList<List<Song>>()
    for (members in components.values) {
        if (members.size < 2) continue
        val hasConflict = notDuplicates.isNotEmpty() && members.any { a ->
            members.any { b ->
                a < b && notDuplicates.contains(canonPair(songs[a].fingerprint, songs[b].fingerprint))
            }
        }
        if (!hasConflict) {
            result.add(members.map { songs[it] })
        } else {
            for (cluster in splitConflicting(members, songs, adj, notDuplicates)) {
                if (cluster.size > 1) result.add(cluster.map { songs[it] })
            }
        }
    }
    return result.sortedByDescending { wastedBytes(it) }
}

/** Splits a group that contains a "not duplicates" pair into consistent sub-groups. */
private fun splitConflicting(
    members: List<Int>,
    songs: List<Song>,
    adj: Map<Int, Set<Int>>,
    notDuplicates: Set<Pair<String, String>>
): List<List<Int>> {
    // Walk the group breadth-first so every song (after the first) is linked to an earlier one.
    val visited = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    val ordered = ArrayList<Int>()
    queue.addLast(members[0])
    visited.add(members[0])
    while (queue.isNotEmpty()) {
        val x = queue.removeFirst()
        ordered.add(x)
        for (y in adj[x].orEmpty()) if (visited.add(y)) queue.addLast(y)
    }
    val clusters = ArrayList<MutableList<Int>>()
    for (m in ordered) {
        val target = clusters.firstOrNull { c ->
            c.any { adj[m]?.contains(it) == true } &&
                c.none { notDuplicates.contains(canonPair(songs[m].fingerprint, songs[it].fingerprint)) }
        }
        if (target != null) target.add(m) else clusters.add(mutableListOf(m))
    }
    return clusters
}

fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * For each group, marks all songs EXCEPT the "best" one (largest file = usually best
 * quality) for deletion. Returns the set of song ids to delete.
 */
fun autoSelectDuplicates(groups: List<List<Song>>): Set<Long> {
    val toDelete = HashSet<Long>()
    for (group in groups) {
        val keep = group.maxByOrNull { it.sizeBytes } ?: continue
        for (song in group) if (song.id != keep.id) toDelete.add(song.id)
    }
    return toDelete
}

// ---------- Crash log (shows the real error next launch instead of a silent close) ----------

object CrashLog {
    private const val FILE = "last_crash.txt"
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n" +
                    android.util.Log.getStackTraceString(e)
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
    fun read(ctx: Context): String? =
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}

// ---------- Theme ----------

private val BarColor = Color(0xFF12594A)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F7A63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE3),
    onPrimaryContainer = Color(0xFF00382B),
    secondary = Color(0xFF0B5C86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8F5),
    onSecondaryContainer = Color(0xFF00344F),
    background = Color(0xFFF3F8F6),
    onBackground = Color(0xFF16201D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16201D),
    surfaceVariant = Color(0xFFE3EDE9),
    onSurfaceVariant = Color(0xFF52605B),
    outline = Color(0xFF8A9993),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FDCC0),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFCDEFE3),
    secondary = Color(0xFF8BCDF0),
    onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF0F4C6D),
    onSecondaryContainer = Color(0xFFD3E8F5),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDDE5E1),
    surface = Color(0xFF18201D),
    onSurface = Color(0xFFDDE5E1),
    surfaceVariant = Color(0xFF2A3531),
    onSurfaceVariant = Color(0xFFA9B7B1),
    outline = Color(0xFF75847E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        // The whole UI is Hebrew, so always lay it out right-to-left.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

@Composable
private fun AppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BarColor,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}

/** The app logo (two overlapping rings + a music note), drawn in code. */
@Composable
fun AppLogo(logoSize: Dp = 56.dp) {
    Canvas(modifier = Modifier.size(logoSize)) {
        val w = size.width
        val h = size.height
        val ring = Color.White.copy(alpha = 0.55f)
        drawCircle(ring, radius = w * 0.25f, center = Offset(w * 0.36f, h * 0.5f), style = Stroke(width = w * 0.05f))
        drawCircle(ring, radius = w * 0.25f, center = Offset(w * 0.64f, h * 0.5f), style = Stroke(width = w * 0.05f))
        val white = Color.White
        drawOval(white, topLeft = Offset(w * 0.385f, h * 0.60f), size = Size(w * 0.16f, h * 0.12f))
        drawOval(white, topLeft = Offset(w * 0.565f, h * 0.55f), size = Size(w * 0.16f, h * 0.12f))
        drawLine(white, Offset(w * 0.535f, h * 0.65f), Offset(w * 0.535f, h * 0.33f), strokeWidth = w * 0.03f)
        drawLine(white, Offset(w * 0.715f, h * 0.60f), Offset(w * 0.715f, h * 0.28f), strokeWidth = w * 0.03f)
        drawLine(white, Offset(w * 0.535f, h * 0.34f), Offset(w * 0.715f, h * 0.29f), strokeWidth = w * 0.06f, cap = StrokeCap.Butt)
    }
}

/** Top-bar shortcut to the trash, with a small badge showing how many songs are in it. */
@Composable
private fun TrashIconButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") }
        }) {
            Icon(Icons.Filled.Delete, contentDescription = "פח")
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        val crash = CrashLog.read(this)
        setContent { AppTheme { AppRoot(lastCrash = crash) } }
    }
}

private enum class Screen { Scan, Results, Ignored, Trash, About }

private data class Preset(val label: String, val sim: Float, val tol: Float)

private val PRESETS = listOf(
    Preset("מדויק", 0.92f, 2f),
    Preset("מאוזן", 0.80f, 3f),
    Preset("רחב", 0.65f, 8f)
)

@Composable
fun AppRoot(lastCrash: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { NotDuplicateStore(context) }
    val prefs = remember { AppPrefs(context) }

    var screen by remember { mutableStateOf(Screen.Scan) }
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    var simThreshold by remember { mutableStateOf(prefs.sim) }
    var durTolerance by remember { mutableStateOf(prefs.tol) }
    var lastSim by remember { mutableStateOf(0.8) }
    var lastTol by remember { mutableStateOf(3L) }
    var useTrash by remember { mutableStateOf(prefs.useTrash) }
    var scanning by remember { mutableStateOf(false) }
    var scanPhase by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf(0f) } // 0f..1f, -1f = indeterminate
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var groups by remember { mutableStateOf<List<List<Song>>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmIds by remember { mutableStateOf<Set<Long>?>(null) }
    var markGroup by remember { mutableStateOf<List<Song>?>(null) }
    var markSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var ignoredPairs by remember { mutableStateOf(store.asList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showCrash by remember { mutableStateOf(lastCrash != null) }
    var freedBytes by remember { mutableStateOf(prefs.freedBytes) }
    var freedCount by remember { mutableStateOf(prefs.freedCount) }
    var trashed by remember { mutableStateOf<List<TrashedSong>>(emptyList()) }
    var trashLoading by remember { mutableStateOf(false) }
    var trashSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var trashBackTo by remember { mutableStateOf(Screen.Scan) }
    var pendingTrashIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingTrashAction by remember { mutableStateOf(TrashAction.Restore) }

    val trashSupported = Build.VERSION.SDK_INT >= 30
    val trashActive = useTrash && Build.VERSION.SDK_INT >= 30

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun toastWithAction(msg: String, label: String, onAction: () -> Unit) {
        scope.launch {
            val r = snackbar.showSnackbar(msg, actionLabel = label, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** Reloads the list of files currently in the system trash. */
    fun refreshTrash() {
        if (!trashSupported || !hasPermission) return
        scope.launch {
            trashLoading = true
            val list = withContext(Dispatchers.IO) {
                runCatching { loadTrashedSongs(context) }.getOrDefault(emptyList())
            }
            trashed = list
            trashSelected = trashSelected intersect list.map { it.id }.toSet()
            trashLoading = false
        }
    }

    fun openTrash() {
        if (screen != Screen.Trash) trashBackTo = screen
        trashSelected = emptySet()
        refreshTrash()
        screen = Screen.Trash
    }

    /** After files come back from the trash, refresh the library so they show up again. */
    fun reloadAfterRestore() {
        if (allSongs.isEmpty()) return
        scope.launch {
            val songs = withContext(Dispatchers.IO) { loadSongs(context) }
            val ignored = store.snapshot()
            val sim = lastSim
            val tol = lastTol
            val result = withContext(Dispatchers.Default) { findDuplicateGroups(songs, sim, tol, ignored) }
            allSongs = songs
            groups = result
            selected = emptySet()
        }
    }

    LaunchedEffect(hasPermission) { refreshTrash() }

    if (showCrash && lastCrash != null) {
        AlertDialog(
            onDismissRequest = { showCrash = false; CrashLog.clear(context) },
            title = { Text("האפליקציה נסגרה בגלל שגיאה") },
            text = { Text(lastCrash.take(2000)) },
            confirmButton = {
                TextButton(onClick = { showCrash = false; CrashLog.clear(context) }) { Text("סגור") }
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    fun removeIds(ids: Set<Long>) {
        allSongs = allSongs.filterNot { it.id in ids }
        groups = groups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        selected = selected - ids
    }

    /** Adds the given songs to the "total cleaned so far" statistics. Call before removeIds. */
    fun recordFreed(ids: Set<Long>) {
        val bytes = allSongs.filter { it.id in ids }.sumOf { it.sizeBytes }
        prefs.freedBytes = prefs.freedBytes + bytes
        prefs.freedCount = prefs.freedCount + ids.size
        freedBytes = prefs.freedBytes
        freedCount = prefs.freedCount
    }

    // Handles the "confirm" system dialog required on Android 10+ (API 29+).
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val count = pendingDeleteIds.size
            recordFreed(pendingDeleteIds)
            removeIds(pendingDeleteIds)
            if (trashActive) {
                toastWithAction("$count קבצים הועברו לפח", "פתח פח") { openTrash() }
                refreshTrash()
            } else {
                toast("$count קבצים נמחקו")
            }
        }
        pendingDeleteIds = emptySet()
    }

    // Restore from trash / delete forever: the system asks the user to confirm.
    val trashRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val ids = pendingTrashIds
            val n = ids.size
            if (pendingTrashAction == TrashAction.Restore) {
                // These files were counted as "cleaned" when they went to the trash; take them back.
                val bytes = trashed.filter { it.id in ids }.sumOf { it.sizeBytes }
                prefs.freedBytes = (prefs.freedBytes - bytes).coerceAtLeast(0L)
                prefs.freedCount = (prefs.freedCount - n).coerceAtLeast(0)
                freedBytes = prefs.freedBytes
                freedCount = prefs.freedCount
                toast("$n קבצים שוחזרו לספרייה")
                reloadAfterRestore()
            } else {
                toast("$n קבצים נמחקו לצמיתות")
            }
            trashed = trashed.filterNot { it.id in ids }
            trashSelected = trashSelected - ids
            refreshTrash()
        }
        pendingTrashIds = emptySet()
    }

    fun trashRequest(ids: Set<Long>, action: TrashAction) {
        if (ids.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val uris = ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                val pi = if (action == TrashAction.Restore)
                    MediaStore.createTrashRequest(context.contentResolver, uris, false)
                else
                    MediaStore.createDeleteRequest(context.contentResolver, uris)
                pendingTrashIds = ids
                pendingTrashAction = action
                trashRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                toast("הפעולה נכשלה: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(perm)
    }

    fun runScan() {
        prefs.sim = simThreshold
        prefs.tol = durTolerance
        prefs.useTrash = useTrash
        scanning = true
        errorMsg = null
        scanProgress = -1f
        scanPhase = "טוען את ספריית השירים..."
        val sim = simThreshold.toDouble()
        val tol = durTolerance.toLong()
        scope.launch {
            try {
                val songs = withContext(Dispatchers.IO) { loadSongs(context) }

                scanPhase = "משווה שירים..."
                scanProgress = 0f
                val progressCounter = AtomicInteger(0)
                val progressTotal = AtomicInteger(songs.size.coerceAtLeast(1))

                // A lightweight ticker updates the Compose state a few times a second
                // instead of on every comparison, so the UI stays smooth.
                val tickerJob = launch {
                    while (isActive) {
                        scanProgress = progressCounter.get().toFloat() / progressTotal.get()
                        delay(80)
                    }
                }

                val ignored = store.snapshot()
                val result = withContext(Dispatchers.Default) {
                    findDuplicateGroups(songs, sim, tol, ignored) { done, total ->
                        progressCounter.set(done)
                        progressTotal.set(total)
                    }
                }
                tickerJob.cancel()
                scanProgress = 1f

                lastSim = sim
                lastTol = tol
                allSongs = songs
                groups = result
                selected = emptySet()
                screen = if (result.isNotEmpty()) Screen.Results else Screen.Scan
            } catch (e: Throwable) {
                errorMsg = "שגיאה בסריקה: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                scanning = false
            }
        }
    }

    /** Re-groups the already loaded songs (used after changing "not duplicates" marks). */
    fun recompute() {
        if (allSongs.isEmpty()) return
        val songs = allSongs
        val sim = lastSim
        val tol = lastTol
        scope.launch {
            val ignored = store.snapshot()
            val result = withContext(Dispatchers.Default) { findDuplicateGroups(songs, sim, tol, ignored) }
            groups = result
            val stillThere = result.flatten().map { it.id }.toSet()
            selected = selected intersect stillThere
        }
    }

    /** Deletes (or moves to trash) the given song ids. On Android 10+ the system asks once. */
    fun deleteIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = if (useTrash)
                            MediaStore.createTrashRequest(context.contentResolver, uris, true)
                        else
                            MediaStore.createDeleteRequest(context.contentResolver, uris)
                        withContext(Dispatchers.Main) {
                            pendingDeleteIds = ids
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    } else {
                        // Older Android: try a direct delete per file; may prompt on API 29.
                        var deleted = false
                        for (uri in uris) {
                            try {
                                context.contentResolver.delete(uri, null, null)
                                deleted = true
                            } catch (e: SecurityException) {
                                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                                    withContext(Dispatchers.Main) {
                                        pendingDeleteIds = ids
                                        deleteRequestLauncher.launch(
                                            IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                                        )
                                    }
                                    return@withContext
                                }
                            }
                        }
                        if (deleted) withContext(Dispatchers.Main) { recordFreed(ids); removeIds(ids) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMsg = "המחיקה נכשלה: ${e.message ?: e.javaClass.simpleName}" }
                }
            }
        }
    }

    fun playSong(song: Song) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(song.uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            toast("לא נמצאה אפליקציית נגן במכשיר שיכולה לפתוח את הקובץ")
        }
    }

    /** Shares the list of duplicate groups as plain text (WhatsApp, email, notes...). */
    fun exportList() {
        try {
            val sb = StringBuilder()
            sb.append("רשימת שירים כפולים\n\n")
            groups.forEachIndexed { i, g ->
                sb.append("קבוצה ${i + 1}\n")
                g.forEach { sb.append("  ${it.path}  (${formatSize(it.sizeBytes)}, ${formatDuration(it.durationSec)})\n") }
                sb.append("\n")
            }
            val text = if (sb.length > 200_000) sb.substring(0, 200_000) + "\n... (הרשימה קוצרה)" else sb.toString()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "רשימת שירים כפולים")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "ייצוא הרשימה"))
        } catch (e: Exception) {
            toast("הייצוא נכשל")
        }
    }

    // ----- Dialogs -----

    confirmIds?.let { ids ->
        val bytes = allSongs.filter { it.id in ids }.sumOf { it.sizeBytes }
        val fullGroups = groups.count { g -> g.isNotEmpty() && g.all { it.id in ids } }
        AlertDialog(
            onDismissRequest = { confirmIds = null },
            title = { Text(if (trashActive) "להעביר לפח?" else "למחוק לצמיתות?") },
            text = {
                Text(
                    "${ids.size} קבצים (${formatSize(bytes)}). " +
                        (if (trashActive) "אפשר יהיה לשחזר אותם מהפח של הגלריה/הקבצים."
                        else "לא ניתן לשחזר אחרי המחיקה.") +
                        (if (fullGroups > 0)
                            "\n\n⚠️ ב-$fullGroups קבוצות נבחרו כל העותקים, ולכן השיר יימחק לגמרי מהמכשיר."
                        else "")
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmIds = null; deleteIds(ids) }) {
                    Text(if (trashActive) "העבר לפח" else "מחק", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmIds = null }) { Text("ביטול") } }
        )
    }

    markGroup?.let { g ->
        val checkedCount = g.count { it.id in markSelected }
        val allChecked = checkedCount == g.size
        val toggleAll = {
            markSelected = if (allChecked) emptySet() else g.map { it.id }.toSet()
        }
        AlertDialog(
            onDismissRequest = { markGroup = null },
            title = { Text("אלה לא באמת אותו שיר?") },
            text = {
                Column {
                    Text(
                        "סמן את השירים שאינם כפולים זה של זה. האפליקציה תזכור את זה " +
                            "ולא תציג אותם שוב ככפולים בסריקות הבאות.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { toggleAll() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TriStateCheckbox(
                            state = when {
                                allChecked -> ToggleableState.On
                                checkedCount == 0 -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = { toggleAll() }
                        )
                        Text(
                            if (allChecked) "בטל סימון של כולם" else "סמן את כולם (${g.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider()
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        g.forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        markSelected = if (s.id in markSelected) markSelected - s.id else markSelected + s.id
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = s.id in markSelected,
                                    onCheckedChange = { c ->
                                        markSelected = if (c) markSelected + s.id else markSelected - s.id
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${formatDuration(s.durationSec)} · ${formatSize(s.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = checkedCount >= 2,
                    onClick = {
                        store.addAll(g.filter { it.id in markSelected }.map { it.fingerprint })
                        ignoredPairs = store.asList()
                        markGroup = null
                        recompute()
                        toast("נשמר — השירים האלה לא יזוהו שוב ככפולים")
                    }
                ) { Text(if (checkedCount >= 2) "שמור ($checkedCount)" else "שמור") }
            },
            dismissButton = { TextButton(onClick = { markGroup = null }) { Text("ביטול") } }
        )
    }

    // ----- Screens -----

    // System back returns to the main screen instead of closing the app.
    BackHandler(enabled = screen != Screen.Scan) {
        screen = if (screen == Screen.Trash) trashBackTo else Screen.Scan
    }

    val showNavBar = screen != Screen.About
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showNavBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == Screen.Scan || screen == Screen.Ignored,
                        onClick = { screen = Screen.Scan },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("סריקה") }
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Results,
                        enabled = allSongs.isNotEmpty(),
                        onClick = { screen = Screen.Results },
                        icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        label = { Text("תוצאות") }
                    )
                    if (trashSupported && hasPermission) {
                        NavigationBarItem(
                            selected = screen == Screen.Trash,
                            onClick = { if (screen != Screen.Trash) openTrash() },
                            icon = {
                                BadgedBox(badge = {
                                    if (trashed.isNotEmpty()) Badge { Text(if (trashed.size > 99) "99+" else "${trashed.size}") }
                                }) { Icon(Icons.Filled.Delete, contentDescription = null) }
                            },
                            label = { Text("פח") }
                        )
                    }
                }
            }
        }
    ) { navPadding ->
    Box(Modifier.padding(navPadding).consumeWindowInsets(navPadding)) {
    when (screen) {
        Screen.Scan -> ScanScreen(
            snackbar = snackbar,
            hasPermission = hasPermission,
            onRequestPermission = { requestPermission() },
            sim = simThreshold,
            onSim = { simThreshold = it },
            tol = durTolerance,
            onTol = { durTolerance = it },
            useTrash = useTrash,
            onUseTrash = { useTrash = it; prefs.useTrash = it },
            freedBytes = freedBytes,
            freedCount = freedCount,
            scanning = scanning,
            phase = scanPhase,
            progress = scanProgress,
            onScan = { runScan() },
            hasScanned = allSongs.isNotEmpty(),
            songsCount = allSongs.size,
            groupsCount = groups.size,
            wasted = groups.sumOf { wastedBytes(it) },
            errorMsg = errorMsg,
            ignoredCount = ignoredPairs.size,
            trashSupported = trashSupported,
            trashCount = trashed.size,
            onOpenTrash = { openTrash() },
            onShowResults = { screen = Screen.Results },
            onShowIgnored = { screen = Screen.Ignored },
            onAbout = { screen = Screen.About }
        )

        Screen.Results -> ResultsScreen(
            snackbar = snackbar,
            groups = groups,
            selected = selected,
            trashActive = trashActive,
            trashSupported = trashSupported,
            trashCount = trashed.size,
            onOpenTrash = { openTrash() },
            onToggle = { id, checked -> selected = if (checked) selected + id else selected - id },
            onAutoSelect = { selected = autoSelectDuplicates(groups) },
            onClear = { selected = emptySet() },
            onSetSelected = { selected = it },
            onExport = { exportList() },
            onPlay = { playSong(it) },
            onDelete = { confirmIds = it },
            onMark = { g ->
                markSelected = if (g.size == 2) g.map { it.id }.toSet() else emptySet()
                markGroup = g
            },
            onBack = { screen = Screen.Scan }
        )

        Screen.Ignored -> IgnoredScreen(
            snackbar = snackbar,
            pairs = ignoredPairs,
            onRemove = { p ->
                store.remove(p)
                ignoredPairs = store.asList()
                recompute()
            },
            onRemoveMany = { set ->
                set.forEach { store.remove(it) }
                ignoredPairs = store.asList()
                recompute()
            },
            onClearAll = {
                store.clear()
                ignoredPairs = store.asList()
                recompute()
            },
            onBack = { screen = Screen.Scan }
        )

        Screen.Trash -> TrashScreen(
            snackbar = snackbar,
            songs = trashed,
            loading = trashLoading,
            selected = trashSelected,
            onToggle = { id, checked -> trashSelected = if (checked) trashSelected + id else trashSelected - id },
            onSetSelected = { trashSelected = it },
            onRestore = { trashRequest(it, TrashAction.Restore) },
            onDeleteForever = { trashRequest(it, TrashAction.DeleteForever) },
            onBack = { screen = trashBackTo }
        )

        Screen.About -> AboutScreen(onBack = { screen = Screen.Scan })
    }
    }
    }
}

// ---------- Scan screen ----------

@Composable
private fun ScanScreen(
    snackbar: SnackbarHostState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    sim: Float,
    onSim: (Float) -> Unit,
    tol: Float,
    onTol: (Float) -> Unit,
    useTrash: Boolean,
    onUseTrash: (Boolean) -> Unit,
    scanning: Boolean,
    phase: String,
    progress: Float,
    onScan: () -> Unit,
    hasScanned: Boolean,
    songsCount: Int,
    groupsCount: Int,
    wasted: Long,
    errorMsg: String?,
    ignoredCount: Int,
    trashSupported: Boolean,
    trashCount: Int,
    onOpenTrash: () -> Unit,
    onShowResults: () -> Unit,
    onShowIgnored: () -> Unit,
    onAbout: () -> Unit,
    freedBytes: Long,
    freedCount: Int
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppBar(title = "מציאת שירים כפולים", actions = {
                if (trashSupported && hasPermission) TrashIconButton(trashCount, onOpenTrash)
                IconButton(onClick = onAbout) { Icon(Icons.Filled.Info, contentDescription = "אודות") }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1F8A70), Color(0xFF0B3D5C))))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(logoSize = 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "מנקים את הספרייה",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "מוצאים שירים כפולים בלי אינטרנט — הכול נשאר במכשיר שלך.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            if (!hasPermission) {
                SectionCard {
                    Text("כדי לסרוק את השירים במכשיר צריך לאשר גישה לקבצי מדיה.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("אישור הרשאה") }
                }
                return@Column
            }

            // Sensitivity
            SectionCard {
                Text("רגישות הסריקה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { p ->
                        FilterChip(
                            selected = sim == p.sim && tol == p.tol,
                            onClick = { onSim(p.sim); onTol(p.tol) },
                            enabled = !scanning,
                            label = { Text(p.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("דמיון בשם: ${(sim * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = sim, onValueChange = onSim, valueRange = 0.5f..1f, enabled = !scanning)
                Text("הפרש מותר באורך השיר: ${tol.toInt()} שניות", style = MaterialTheme.typography.bodyMedium)
                Slider(value = tol, onValueChange = onTol, valueRange = 0f..15f, enabled = !scanning)
                Text(
                    "דמיון גבוה + הפרש קטן = פחות תוצאות אבל מדויקות יותר.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (Build.VERSION.SDK_INT >= 30) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("העבר לפח במקום למחוק", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "אפשר לשחזר בטעות שנייה",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = useTrash, onCheckedChange = onUseTrash)
                    }
                }
            }

            Button(
                onClick = onScan,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "סורק..." else "סרוק את המכשיר", style = MaterialTheme.typography.titleMedium)
            }

            if (scanning) {
                SectionCard {
                    Text(phase, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (progress < 0f) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (hasScanned && !scanning) {
                SectionCard {
                    Text("תוצאות הסריקה האחרונה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("$songsCount שירים")
                        Pill("$groupsCount קבוצות")
                        if (wasted > 0) Pill("~${formatSize(wasted)} לפינוי")
                    }
                    Spacer(Modifier.height(12.dp))
                    if (groupsCount > 0) {
                        Button(onClick = onShowResults, modifier = Modifier.fillMaxWidth()) { Text("הצג תוצאות") }
                    } else {
                        Text("לא נמצאו כפילויות בקריטריונים הנוכחיים 🎉")
                    }
                }
            }

            if (freedCount > 0) {
                SectionCard {
                    Text("סה״כ נוקו עד היום", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$freedCount קבצים · ${formatSize(freedBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (trashSupported) {
                OutlinedButton(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (trashCount > 0) "הפח של השירים ($trashCount)" else "הפח של השירים")
                }
            }

            if (ignoredCount > 0) {
                OutlinedButton(onClick = onShowIgnored, modifier = Modifier.fillMaxWidth()) {
                    Text("שירים שסימנת כ\"לא כפולים\" ($ignoredCount)")
                }
            }
        }
    }
}

// ---------- Results screen ----------

private enum class SortMode(val label: String) {
    BY_WASTE("הכי הרבה מקום"),
    BY_COUNT("הכי הרבה כפולים"),
    BY_NAME("א-ב")
}

@Composable
private fun ResultsScreen(
    snackbar: SnackbarHostState,
    groups: List<List<Song>>,
    selected: Set<Long>,
    trashActive: Boolean,
    trashSupported: Boolean,
    trashCount: Int,
    onOpenTrash: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onAutoSelect: () -> Unit,
    onClear: () -> Unit,
    onSetSelected: (Set<Long>) -> Unit,
    onPlay: (Song) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onMark: (List<Song>) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.BY_WASTE) }
    var showFolders by remember { mutableStateOf(false) }

    val shown = remember(groups, query, sortMode) {
        val filtered = if (query.isBlank()) groups
        else groups.filter { g ->
            g.any { it.title.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        }
        when (sortMode) {
            SortMode.BY_WASTE -> filtered.sortedByDescending { wastedBytes(it) }
            SortMode.BY_COUNT -> filtered.sortedByDescending { it.size }
            SortMode.BY_NAME -> filtered.sortedBy { it.firstOrNull()?.title?.lowercase() ?: "" }
        }
    }

    val shownIds = remember(shown) { shown.flatten().map { it.id }.toSet() }
    val allShownSelected = shownIds.isNotEmpty() && selected.containsAll(shownIds)

    val folderCounts = remember(groups) {
        groups.flatten()
            .groupBy { it.folder }
            .map { entry -> Pair(entry.key, entry.value.size) }
            .sortedByDescending { it.second }
    }

    val totalWasted = groups.sumOf { wastedBytes(it) }
    val selectedBytes = groups.flatten().filter { it.id in selected }.sumOf { it.sizeBytes }

    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("סמן לפי תיקייה") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "בחרו תיקייה כדי לסמן את כל השירים הכפולים שנמצאים בה. " +
                            "קבוצות שכל העותקים שלהן באותה תיקייה מדולגות, כדי לא למחוק שיר לגמרי.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    folderCounts.forEach { fc ->
                        val folderPath = fc.first
                        TextButton(
                            onClick = {
                                val ids = HashSet<Long>()
                                for (g in groups) {
                                    val inFolder = g.filter { it.folder == folderPath }
                                    if (inFolder.isNotEmpty() && inFolder.size < g.size) {
                                        ids.addAll(inFolder.map { it.id })
                                    }
                                }
                                onSetSelected(selected + ids)
                                showFolders = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${fc.second} · $folderPath",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolders = false }) { Text("סגור") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppBar(title = "תוצאות", onBack = onBack, actions = {
                if (trashSupported) TrashIconButton(trashCount, onOpenTrash)
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Share, contentDescription = "ייצוא הרשימה")
                }
            })
        },
        bottomBar = {
            // The bottom bar only appears once something is selected, so the list gets the whole screen.
            if (selected.isNotEmpty()) {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "נבחרו ${selected.size} קבצים",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "יפונו ${formatSize(selectedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { onDelete(selected) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (trashActive) "העבר לפח" else "מחק")
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Everything (summary, search, sort, actions) lives INSIDE the list and scrolls away,
        // so the song groups can use the full height of the screen.
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
        ) {
            item(key = "summary") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("${groups.size} קבוצות")
                    if (totalWasted > 0) Pill("~${formatSize(totalWasted)} לפינוי")
                }
            }

            if (trashSupported && trashCount > 0) {
                item(key = "trashBanner") {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenTrash() },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "בפח יש $trashCount שירים — הקש לשחזור או לריקון",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (groups.isNotEmpty()) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("חיפוש לפי שם או תיקייה...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "נקה חיפוש")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                item(key = "sort") {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortMode.values().forEach { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { sortMode = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                }
            }

            item(key = "actions") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSetSelected(if (allShownSelected) selected - shownIds else selected + shownIds) },
                        enabled = shownIds.isNotEmpty()
                    ) { Text(if (allShownSelected) "בטל סימון הכול" else "סמן הכול (${shownIds.size})") }
                    OutlinedButton(onClick = onAutoSelect) { Text("סמן כפולים אוטומטית") }
                    OutlinedButton(onClick = { showFolders = true }, enabled = groups.isNotEmpty()) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("לפי תיקייה")
                    }
                    if (selected.isNotEmpty()) TextButton(onClick = onClear) { Text("נקה בחירה") }
                }
            }

            item(key = "hint") {
                Text(
                    "⭐ = הקובץ הגדול ביותר (בדרך כלל האיכות הטובה ביותר). הקש על שיר כדי לנגן ולוודא.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "כל הכפילויות טופלו 🎉",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else if (shown.isEmpty()) {
                item(key = "nomatch") {
                    Text(
                        "אין תוצאות עבור \"$query\"",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            itemsIndexed(shown) { index, group ->
                val best = group.maxByOrNull { it.sizeBytes }
                val sizeCounts = group.groupingBy { it.sizeBytes }.eachCount()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "קבוצה ${index + 1} · ${group.size} קבצים",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Pill("~${formatSize(wastedBytes(group))}")
                        }
                        val groupIds = group.map { it.id }.toSet()
                        val groupAllSelected = selected.containsAll(groupIds)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(onClick = {
                                onSetSelected(if (groupAllSelected) selected - groupIds else selected + groupIds)
                            }) { Text(if (groupAllSelected) "בטל סימון קבוצה" else "סמן קבוצה") }
                            TextButton(onClick = {
                                val others = group.filter { it.id != best?.id }.map { it.id }
                                onSetSelected(selected + others)
                            }) { Text("השאר רק את הטוב ביותר") }
                        }
                        // Always-visible button (previously hidden inside the horizontally scrolling row).
                        OutlinedButton(
                            onClick = { onMark(group) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("סמן כ\"לא כפולים\" (לא יזוהו שוב בסריקות)") }
                        group.forEach { song ->
                            val details = buildString {
                                append(formatDuration(song.durationSec))
                                append(" · ")
                                append(formatSize(song.sizeBytes))
                                if (song.bitrateKbps > 0) append(" · ~${song.bitrateKbps} kbps")
                                if ((sizeCounts[song.sizeBytes] ?: 0) > 1) append(" · גודל זהה")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = song.id in selected,
                                    onCheckedChange = { checked -> onToggle(song.id, checked) }
                                )
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { onPlay(song) }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        song.title + if (song.id == best?.id) "  ⭐" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        song.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        overflow = TextOverflow.Visible,
                                        softWrap = true
                                    )
                                }
                                IconButton(onClick = { onPlay(song) }) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "נגן",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onDelete(setOf(song.id)) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "מחק",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Trash screen ----------

@Composable
private fun TrashScreen(
    snackbar: SnackbarHostState,
    songs: List<TrashedSong>,
    loading: Boolean,
    selected: Set<Long>,
    onToggle: (Long, Boolean) -> Unit,
    onSetSelected: (Set<Long>) -> Unit,
    onRestore: (Set<Long>) -> Unit,
    onDeleteForever: (Set<Long>) -> Unit,
    onBack: () -> Unit
) {
    var confirmForever by remember { mutableStateOf(false) }
    val selectedBytes = songs.filter { it.id in selected }.sumOf { it.sizeBytes }
    val allChecked = songs.isNotEmpty() && songs.all { it.id in selected }

    if (confirmForever) {
        AlertDialog(
            onDismissRequest = { confirmForever = false },
            title = { Text("למחוק לצמיתות?") },
            text = {
                Text("${selected.size} קבצים (${formatSize(selectedBytes)}) יימחקו ולא ניתן יהיה לשחזר אותם.")
            },
            confirmButton = {
                TextButton(onClick = { confirmForever = false; onDeleteForever(selected) }) {
                    Text("מחק לצמיתות", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForever = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppBar(title = "הפח", onBack = onBack) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            "נבחרו ${selected.size} קבצים · ${formatSize(selectedBytes)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRestore(selected) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Restore, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("שחזר")
                            }
                            Button(
                                onClick = { confirmForever = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("מחק לצמיתות")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
        ) {
            item(key = "info") {
                Text(
                    "שירים שהועברו לפח. הם נמחקים אוטומטית אחרי כ-30 יום, ועד אז אפשר לשחזר אותם.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (songs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (loading) "טוען..." else "הפח ריק",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                item(key = "bulkActions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onRestore(songs.map { it.id }.toSet()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("שחזר הכול (${songs.size})") }
                        OutlinedButton(
                            onClick = { onSetSelected(songs.map { it.id }.toSet()); confirmForever = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("רוקן את הפח") }
                    }
                }
                item(key = "selectAll") {
                    val toggleAll = {
                        onSetSelected(if (allChecked) emptySet() else songs.map { it.id }.toSet())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { toggleAll() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TriStateCheckbox(
                            state = when {
                                allChecked -> ToggleableState.On
                                selected.isEmpty() -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = { toggleAll() }
                        )
                        Text(
                            if (allChecked) "בטל סימון של כולם" else "סמן הכול (${songs.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                itemsIndexed(songs, key = { _, song -> song.id }) { _, song ->
                    val days = song.daysLeft()
                    val details = buildString {
                        append(formatDuration(song.durationSec))
                        append(" · ")
                        append(formatSize(song.sizeBytes))
                        if (days >= 0) append(" · יימחק בעוד $days ימים")
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(song.id, song.id !in selected) }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = song.id in selected,
                                onCheckedChange = { c -> onToggle(song.id, c) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (song.folder.isNotEmpty()) {
                                    Text(
                                        song.folder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- "Not duplicates" management screen ----------

@Composable
private fun IgnoredScreen(
    snackbar: SnackbarHostState,
    pairs: List<Pair<String, String>>,
    onRemove: (Pair<String, String>) -> Unit,
    onRemoveMany: (Set<Pair<String, String>>) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
    var checked by remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }
    val sel = remember(checked, pairs) { checked.filter { it in pairs }.toSet() }
    val allSel = pairs.isNotEmpty() && sel.size == pairs.size
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppBar(title = "סימונים של \"לא כפולים\"", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "זוגות שירים שסימנת כשונים. הם לא יוצגו ככפולים בסריקות. אפשר לבטל סימון בכל רגע.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (pairs.isEmpty()) {
                Text("אין סימונים.", style = MaterialTheme.typography.titleMedium)
            } else {
                val toggleAll = { checked = if (allSel) emptySet() else pairs.toSet() }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { toggleAll() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TriStateCheckbox(
                        state = when {
                            allSel -> ToggleableState.On
                            sel.isEmpty() -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = { toggleAll() }
                    )
                    Text(
                        if (allSel) "בטל סימון של כולם" else "סמן הכול (${pairs.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sel.isNotEmpty()) {
                        Button(onClick = { onRemoveMany(sel); checked = emptySet() }) {
                            Text("בטל סימון ל-${sel.size} מסומנים")
                        }
                    }
                    OutlinedButton(onClick = onClearAll) { Text("בטל את כל הסימונים") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pairs) { _, p ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = p in sel,
                                    onCheckedChange = { c -> checked = if (c) sel + p else sel - p }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(fingerprintName(p.first), style = MaterialTheme.typography.bodyMedium)
                                    Text("≠", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(fingerprintName(p.second), style = MaterialTheme.typography.bodyMedium)
                                }
                                TextButton(onClick = { onRemove(p) }) { Text("בטל סימון") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- About screen ----------

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }
    Scaffold(topBar = { AppBar(title = "אודות", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1F8A70), Color(0xFF0B3D5C))))
                    .padding(20.dp)
            ) { AppLogo(logoSize = 96.dp) }
            Spacer(Modifier.height(16.dp))
            Text("מציאת שירים כפולים", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (version.isNotEmpty()) {
                Text(
                    "גרסה $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "אפליקציה לזיהוי שירים כפולים במכשיר לפי דמיון בשם הקובץ ואורך השיר, " +
                    "פועלת כולה במכשיר בלי חיבור לרשת.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "פותח על ידי יהודי לא פשוט @מתמחים טופ 🔥",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun hasAudioPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO
    else
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
        PackageManager.PERMISSION_GRANTED
}
