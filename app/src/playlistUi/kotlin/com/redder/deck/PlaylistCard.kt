package com.redder.deck

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.redder.R
import com.redder.flavor.CurrentFlavor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_MS = 15_000L
private const val ROWS_SHOWN = 12
private const val RECENT_SHOWN = 5
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_TWO_DAYS = 48L
private const val HOURS_PER_DAY = 24L

/**
 * The playlist on the dashboard: what is next, whether it is ready, Done and undo on each row.
 *
 * @param onSeeAll Opens the whole list, in a build that has that screen.
 */
@Composable
fun PlaylistCard(onSeeAll: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val repository = remember { CurrentFlavor.playlistSource(context) }
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsState()
    var tick by remember { mutableStateOf(0) }

    // A stopped screen keeps its composition, so without this the list would go on refreshing in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.syncOnce()
                tick++
                delay(POLL_MS)
            }
        }
    }

    val rows = remember(state, tick) { repository.visibleRows(state) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.playlist_title), style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(
                onClick = { context.startActivity(NowPlayingActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            ) {
                Text(stringResource(R.string.action_now_playing))
            }
            onSeeAll?.let { open ->
                TextButton(onClick = open) { Text(stringResource(R.string.playlist_see_all)) }
            }
            if (state.offline) {
                Text(
                    text = stringResource(R.string.playlist_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.stale.forEach { stale ->
                val name = "r/${stale.sub}"
                Text(
                    text =
                        if (stale.lastOk != null) {
                            stringResource(R.string.playlist_stale, name, age(stale.lastOk))
                        } else {
                            stringResource(R.string.playlist_waiting, name)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loaded && rows.isEmpty()) {
                Text(text = stringResource(R.string.playlist_empty), style = MaterialTheme.typography.bodyMedium)
            }
            rows.take(ROWS_SHOWN).forEach { row ->
                PlaylistRowView(
                    row = row,
                    buttonLabel = stringResource(R.string.action_done),
                    buttonDescription = stringResource(R.string.playlist_done_row, row.title),
                    onClick = {
                        repository.done(row)
                        tick++
                    },
                )
            }
            if (state.recent.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.playlist_recent),
                    style = MaterialTheme.typography.titleSmall,
                )
                state.recent.take(RECENT_SHOWN).forEach { row ->
                    PlaylistRowView(
                        row = row,
                        buttonLabel = stringResource(R.string.action_undo),
                        buttonDescription = stringResource(R.string.playlist_undo_row, row.title),
                        onClick = {
                            repository.undone(row.postId)
                            scope.launch { repository.syncOnce() }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistRowView(
    row: PlaylistRow,
    buttonLabel: String,
    buttonDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.playlist_meta, row.sub, lengthText(row.lengthMs), stateText(row.state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (buttonLabel == stringResource(R.string.action_done)) {
            FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = buttonDescription }) {
                Text(buttonLabel)
            }
        } else {
            TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = buttonDescription }) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun lengthText(ms: Long?): String {
    if (ms == null) return stringResource(R.string.playlist_length_unknown)
    val minutes = ms / MS_PER_MINUTE
    return if (minutes < 1) {
        stringResource(
            R.string.playlist_length_short,
        )
    } else {
        stringResource(R.string.playlist_length_minutes, minutes.toInt())
    }
}

@Composable
private fun stateText(state: String): String =
    when (state) {
        "new" -> stringResource(R.string.playlist_state_new)
        "prepared" -> stringResource(R.string.playlist_state_prepared)
        "started" -> stringResource(R.string.playlist_state_started)
        else -> state
    }

private fun age(lastOk: Long): String {
    val hours = (System.currentTimeMillis() - lastOk) / MS_PER_HOUR
    return if (hours < HOURS_PER_TWO_DAYS) "$hours h" else "${hours / HOURS_PER_DAY} days"
}
