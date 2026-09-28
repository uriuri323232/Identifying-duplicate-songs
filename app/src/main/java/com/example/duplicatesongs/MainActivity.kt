@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.duplicatesongs

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

private enum class Screen { Scan, Results, Ignored, About }

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

    var screen by remember { mutableStateOf(Screen.Scan) }
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    var simThreshold by remember { mutableStateOf(0.8f) }
    var durTolerance by remember { mutableStateOf(3f) }
    var lastSim by remember { mutableStateOf(0.8) }
    var lastTol by remember { mutableStateOf(3L) }
    var useTrash by remember { mutableStateOf(true) }
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

    val trashActive = useTrash && Build.VERSION.SDK_INT >= 30

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

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

    // Handles the "confirm" system dialog required on Android 10+ (API 29+).
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val count = pendingDeleteIds.size
            removeIds(pendingDeleteIds)
            toast(if (trashActive) "$count קבצים הועברו לפח" else "$count קבצים נמחקו")
        }
        pendingDeleteIds = emptySet()
    }

    fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(perm)
    }

    fun runScan() {
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
                        if (deleted) withContext(Dispatchers.Main) { removeIds(ids) }
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

    // ----- Dialogs -----

    confirmIds?.let { ids ->
        val bytes = allSongs.filter { it.id in ids }.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { confirmIds = null },
            title = { Text(if (trashActive) "להעביר לפח?" else "למחוק לצמיתות?") },
            text = {
                Text(
                    "${ids.size} קבצים (${formatSize(bytes)}). " +
                        if (trashActive) "אפשר יהיה לשחזר אותם מהפח של הגלריה/הקבצים."
                        else "לא ניתן לשחזר אחרי המחיקה."
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
        AlertDialog(
            onDismissRequest = { markGroup = null },
            title = { Text("אלה לא באמת אותו שיר?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "סמן את השירים שאינם כפולים זה של זה. האפליקציה תזכור את זה " +
                            "ולא תציג אותם שוב ככפולים בסריקות הבאות.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
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
            },
            confirmButton = {
                TextButton(
                    enabled = markSelected.size >= 2,
                    onClick = {
                        store.addAll(g.filter { it.id in markSelected }.map { it.fingerprint })
                        ignoredPairs = store.asList()
                        markGroup = null
                        recompute()
                        toast("נשמר — השירים האלה לא יזוהו שוב ככפולים")
                    }
                ) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { markGroup = null }) { Text("ביטול") } }
        )
    }

    // ----- Screens -----

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
            onUseTrash = { useTrash = it },
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
            onShowResults = { screen = Screen.Results },
            onShowIgnored = { screen = Screen.Ignored },
            onAbout = { screen = Screen.About }
        )

        Screen.Results -> ResultsScreen(
            snackbar = snackbar,
            groups = groups,
            selected = selected,
            trashActive = trashActive,
            onToggle = { id, checked -> selected = if (checked) selected + id else selected - id },
            onAutoSelect = { selected = autoSelectDuplicates(groups) },
            onClear = { selected = emptySet() },
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
            onClearAll = {
                store.clear()
                ignoredPairs = store.asList()
                recompute()
            },
            onBack = { screen = Screen.Scan }
        )

        Screen.About -> AboutScreen(onBack = { screen = Screen.Scan })
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
    onShowResults: () -> Unit,
    onShowIgnored: () -> Unit,
    onAbout: () -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppBar(title = "מציאת שירים כפולים", actions = {
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

            if (ignoredCount > 0) {
                OutlinedButton(onClick = onShowIgnored, modifier = Modifier.fillMaxWidth()) {
                    Text("שירים שסימנת כ\"לא כפולים\" ($ignoredCount)")
                }
            }
        }
    }
}

// ---------- Results screen ----------

@Composable
private fun ResultsScreen(
    snackbar: SnackbarHostState,
    groups: List<List<Song>>,
    selected: Set<Long>,
    trashActive: Boolean,
    onToggle: (Long, Boolean) -> Unit,
    onAutoSelect: () -> Unit,
    onClear: () -> Unit,
    onPlay: (Song) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onMark: (List<Song>) -> Unit,
    onBack: () -> Unit
) {
    val totalWasted = groups.sumOf { wastedBytes(it) }
    val selectedBytes = groups.flatten().filter { it.id in selected }.sumOf { it.sizeBytes }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppBar(title = "תוצאות", onBack = onBack) },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (selected.isEmpty()) "לא נבחרו קבצים" else "נבחרו ${selected.size} קבצים",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (selected.isNotEmpty()) {
                            Text(
                                "יפונו ${formatSize(selectedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = { onDelete(selected) },
                        enabled = selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (trashActive) "העבר לפח" else "מחק")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("${groups.size} קבוצות")
                if (totalWasted > 0) Pill("~${formatSize(totalWasted)} לפינוי")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAutoSelect) { Text("סמן כפולים אוטומטית") }
                Spacer(Modifier.width(8.dp))
                if (selected.isNotEmpty()) TextButton(onClick = onClear) { Text("נקה בחירה") }
            }
            Text(
                "⭐ = הקובץ הגדול ביותר (בדרך כלל האיכות הטובה ביותר). הקש על שיר כדי לנגן ולוודא.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            if (groups.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("כל הכפילויות טופלו 🎉", style = MaterialTheme.typography.titleMedium)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(groups) { index, group ->
                    val best = group.maxByOrNull { it.sizeBytes }
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
                                TextButton(onClick = { onMark(group) }) { Text("לא כפולים") }
                            }
                            group.forEach { song ->
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
                                            "${formatDuration(song.durationSec)} · ${formatSize(song.sizeBytes)}",
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
}

// ---------- "Not duplicates" management screen ----------

@Composable
private fun IgnoredScreen(
    snackbar: SnackbarHostState,
    pairs: List<Pair<String, String>>,
    onRemove: (Pair<String, String>) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
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
                OutlinedButton(onClick = onClearAll) { Text("בטל את כל הסימונים") }
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
