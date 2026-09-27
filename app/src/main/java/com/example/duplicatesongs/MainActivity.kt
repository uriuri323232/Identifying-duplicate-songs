package com.example.duplicatesongs

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.duplicatesongs.ui.AboutScreen
import com.example.duplicatesongs.ui.ResultsScreen
import com.example.duplicatesongs.ui.ScanScreen
import com.example.duplicatesongs.ui.theme.DuplicateSongFinderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- Data model ----------

data class Song(
    val id: Long,
    val title: String,
    val path: String,
    val durationSec: Long,
    val sizeBytes: Long,
    val norm: String
) {
    val uri: Uri get() = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
    )

    /** The folder the file lives in (path without the file name), for display purposes. */
    val directory: String
        get() {
            val idx = path.lastIndexOf('/')
            return if (idx > 0) path.substring(0, idx) else path
        }
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
    // Remove content in brackets. Braces are escaped because Android's regex engine
    // treats an unescaped { or } as a quantifier and throws a syntax error.
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
    val m = a.length; val n = b.length
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
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
        return r
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}

// ---------- MediaStore query ----------

fun loadSongs(context: android.content.Context): List<Song> {
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

fun findDuplicateGroups(
    songs: List<Song>,
    simThreshold: Double,
    durationToleranceSec: Long,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): List<List<Song>> {
    val uf = UnionFind(songs.size)
    // Compare only songs with near durations: sort by duration and slide a window,
    // so we avoid the full O(n^2) comparison on large libraries.
    val order = songs.indices.sortedBy { songs[it].durationSec }
    for (p in order.indices) {
        val i = order[p]
        for (q in p + 1 until order.size) {
            val j = order[q]
            if (songs[j].durationSec - songs[i].durationSec > durationToleranceSec) break
            if (similarity(songs[i].norm, songs[j].norm) >= simThreshold) uf.union(i, j)
        }
        onProgress(p + 1, order.size)
    }
    val groups = LinkedHashMap<Int, MutableList<Song>>()
    songs.forEachIndexed { idx, song ->
        groups.getOrPut(uf.find(idx)) { mutableListOf() }.add(song)
    }
    return groups.values.filter { it.size > 1 }
}

fun formatDuration(sec: Long): String {
    val m = sec / 60; val s = sec % 60
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

// ---------- Activity ----------

// ---------- Crash log (shows the real error next launch instead of a silent close) ----------

object CrashLog {
    private const val FILE = "last_crash.txt"
    fun install(ctx: android.content.Context) {
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
    fun read(ctx: android.content.Context): String? =
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    fun clear(ctx: android.content.Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        val crash = CrashLog.read(this)
        setContent { AppRoot(lastCrash = crash) }
    }
}

private enum class Screen { Scan, Results, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(lastCrash: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.Scan) }
    var hasPermission by remember {
        mutableStateOf(hasAudioPermission(context))
    }
    var simThreshold by rememberSaveable { mutableStateOf(0.8f) }
    var durTolerance by rememberSaveable { mutableStateOf(3f) }
    var scanning by remember { mutableStateOf(false) }
    var scanPhase by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf(0f) } // 0f..1f, -1f = indeterminate
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var groups by remember { mutableStateOf<List<List<Song>>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showCrash by remember { mutableStateOf(lastCrash != null) }

    if (showCrash && lastCrash != null) {
        AlertDialog(
            onDismissRequest = { showCrash = false; CrashLog.clear(context) },
            title = { Text("האפליקציה נסגרה בגלל שגיאה") },
            text = { Text(lastCrash.take(2000)) },
            confirmButton = { TextButton(onClick = { showCrash = false; CrashLog.clear(context) }) { Text("סגור") } }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val snackbarHostState = remember { SnackbarHostState() }

    fun removeIds(ids: Set<Long>) {
        allSongs = allSongs.filterNot { it.id in ids }
        groups = groups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        selected = selected - ids
    }

    fun notifyDeleted(ids: Set<Long>) {
        val freedBytes = allSongs.filter { it.id in ids }.sumOf { it.sizeBytes }
        removeIds(ids)
        scope.launch {
            snackbarHostState.showSnackbar("נמחקו ${ids.size} שירים · שוחררו ${formatSize(freedBytes)}")
        }
    }

    // Handles the "confirm delete" system dialog required on Android 10+ (API 29+).
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) notifyDeleted(pendingDeleteIds)
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

                val result = withContext(Dispatchers.Default) {
                    findDuplicateGroups(songs, simThreshold.toDouble(), durTolerance.toLong()) { done, total ->
                        progressCounter.set(done)
                        progressTotal.set(total)
                    }
                }
                tickerJob.cancel()
                scanProgress = 1f

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

    /** Deletes the given song ids. On Android 10+ this shows one system confirmation for all. */
    fun deleteIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                        withContext(Dispatchers.Main) {
                            pendingDeleteIds = ids
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    } else {
                        // Older Android: try direct delete per file; may prompt on API 29.
                        var deleted = false
                        for (uri in uris) {
                            try { context.contentResolver.delete(uri, null, null); deleted = true }
                            catch (e: SecurityException) {
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
                        if (deleted) withContext(Dispatchers.Main) { notifyDeleted(ids) }
                    }
                } catch (e: Exception) { /* ignore */ }
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
            errorMsg = "לא נמצאה אפליקציית נגן במכשיר שיכולה לפתוח את הקובץ"
        }
    }

    DuplicateSongFinderTheme {
        when (screen) {
            Screen.Scan -> ScanScreen(
                hasPermission = hasPermission,
                onRequestPermission = { requestPermission() },
                simThreshold = simThreshold,
                onSimThresholdChange = { simThreshold = it },
                durTolerance = durTolerance,
                onDurToleranceChange = { durTolerance = it },
                scanning = scanning,
                scanPhase = scanPhase,
                scanProgress = scanProgress,
                allSongs = allSongs,
                groupsCount = groups.size,
                errorMsg = errorMsg,
                onScan = { runScan() },
                onShowResults = { screen = Screen.Results },
                onShowAbout = { screen = Screen.About }
            )

            Screen.Results -> ResultsScreen(
                groups = groups,
                selected = selected,
                onSelectedChange = { selected = it },
                onAutoSelect = { selected = autoSelectDuplicates(groups) },
                onDelete = { deleteIds(it) },
                onDeleteSingle = { deleteIds(setOf(it)) },
                onPlay = { playSong(it) },
                onBack = { screen = Screen.Scan },
                snackbarHostState = snackbarHostState
            )

            Screen.About -> AboutScreen(onBack = { screen = Screen.Scan })
        }
    }
}

fun hasAudioPermission(context: android.content.Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO
    else
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
        PackageManager.PERMISSION_GRANTED
}
