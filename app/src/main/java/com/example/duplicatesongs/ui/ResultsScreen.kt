package com.example.duplicatesongs.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.duplicatesongs.Song
import com.example.duplicatesongs.formatDuration
import com.example.duplicatesongs.formatSize
import com.example.duplicatesongs.ui.theme.WasteAmber

private enum class SortMode(val label: String) {
    BY_WASTE("הכי הרבה מקום"),
    BY_COUNT("הכי הרבה כפולים"),
    BY_NAME("א-ב")
}

/** Bytes that would be freed if every song in [group] except the largest one is removed. */
private fun wastedBytes(group: List<Song>): Long {
    val keep = group.maxByOrNull { it.sizeBytes } ?: return 0L
    return group.filter { it.id != keep.id }.sumOf { it.sizeBytes }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    groups: List<List<Song>>,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
    onAutoSelect: () -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onDeleteSingle: (Long) -> Unit,
    onPlay: (Song) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.BY_WASTE) }
    var confirmDelete by remember { mutableStateOf(false) }

    val filtered = remember(groups, query) {
        if (query.isBlank()) groups
        else groups.filter { g -> g.any { it.title.contains(query, ignoreCase = true) } }
    }
    val sorted = remember(filtered, sortMode) {
        when (sortMode) {
            SortMode.BY_WASTE -> filtered.sortedByDescending { wastedBytes(it) }
            SortMode.BY_COUNT -> filtered.sortedByDescending { it.size }
            SortMode.BY_NAME -> filtered.sortedBy { it.firstOrNull()?.title?.lowercase() ?: "" }
        }
    }

    val totalWaste = remember(groups) { groups.sumOf { wastedBytes(it) } }
    val totalDupSongs = remember(groups) { groups.sumOf { it.size } }
    val selectedBytes = remember(selected, groups) {
        groups.flatten().filter { it.id in selected }.sumOf { it.sizeBytes }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("למחוק ${selected.size} שירים?") },
            text = { Text("הפעולה תמחק את הקבצים ישירות מהמכשיר ותשחרר כ-${formatSize(selectedBytes)}. לא ניתן לבטל.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(selected)
                }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("תוצאות")
                            Text(
                                "${groups.size} קבוצות · $totalDupSongs שירים · עד ${formatSize(totalWaste)} לשחרור",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                        }
                    }
                )
                if (groups.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = { Text("חיפוש שיר...") },
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortMode.entries.forEach { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { sortMode = mode },
                                label = { Text(mode.label) },
                                leadingIcon = if (sortMode == mode) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onAutoSelect) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("סמן כפולים אוטומטית")
                }
                Spacer(Modifier.width(8.dp))
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { onSelectedChange(emptySet()) }) { Text("נקה") }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { confirmDelete = true },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selected.isEmpty()) "מחק מסומנים"
                    else "מחק ${selected.size} שירים · ${formatSize(selectedBytes)}"
                )
            }

            if (groups.isEmpty()) {
                EmptyResultsState()
            } else if (sorted.isEmpty()) {
                NoSearchMatchState(query = query, onClear = { query = "" })
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(sorted, key = { g -> g.first().id }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selected = selected,
                            onSelectedChange = onSelectedChange,
                            onPlay = onPlay,
                            onDeleteSingle = onDeleteSingle
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResultsState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("כל הכפילויות טופלו 🎉", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "אין יותר קבוצות כפולות להציג",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoSearchMatchState(query: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text("אין תוצאות עבור \"$query\"", fontSize = 14.sp)
        TextButton(onClick = onClear) { Text("נקה חיפוש") }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: List<Song>,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
    onPlay: (Song) -> Unit,
    onDeleteSingle: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val best = remember(group) { group.maxByOrNull { it.sizeBytes } }
    val waste = remember(group) { wastedBytes(group) }
    val collapseThreshold = 3
    val visibleSongs = if (!expanded && group.size > collapseThreshold) group.take(collapseThreshold) else group

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${group.size} שירים דומים", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("עד ${formatSize(waste)}", fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = WasteAmber,
                        disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    ),
                    border = null
                )
            }

            Spacer(Modifier.height(4.dp))

            visibleSongs.forEach { song ->
                SongRow(
                    song = song,
                    isBest = song.id == best?.id,
                    isSelected = song.id in selected,
                    onCheckedChange = { checked ->
                        onSelectedChange(if (checked) selected + song.id else selected - song.id)
                    },
                    onPlay = { onPlay(song) },
                    onDelete = { onDeleteSingle(song.id) }
                )
            }

            if (group.size > collapseThreshold) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(if (expanded) "הצג פחות" else "הצג עוד ${group.size - collapseThreshold} שירים")
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isBest: Boolean,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (isBest) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = onCheckedChange)

        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onPlay)
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isBest) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "מומלץ לשמירה",
                        tint = WasteAmber,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                "${formatDuration(song.durationSec)} · ${formatSize(song.sizeBytes)}" +
                    if (isBest) " · מומלץ לשמירה" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                song.path,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayCircle, contentDescription = "נגן", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "מחק", tint = MaterialTheme.colorScheme.error)
        }
    }
}
