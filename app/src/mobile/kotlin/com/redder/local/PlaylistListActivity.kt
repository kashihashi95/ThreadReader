package com.redder.local

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.redder.R
import com.redder.deck.PlaylistRowView
import com.redder.model.SubredditQueueHolder
import com.redder.ui.theme.ThreadReaderTheme

/** The whole playlist, in playing order, with Done on each row and Undo on what has just gone. */
class PlaylistListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ThreadReaderTheme { PlaylistListScreen() } }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PlaylistListActivity::class.java)
    }
}

@Composable
private fun PlaylistListScreen() {
    val context = LocalContext.current.applicationContext
    val playlist = remember { LocalPlaylist.forContext(context) }
    val state by playlist.state.collectAsState()
    val queue by SubredditQueueHolder.state.collectAsState()
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.playlist_list_title, state.rows.size),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (state.rows.isEmpty()) {
            item { Text(stringResource(R.string.playlist_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.rows, key = { it.postId }) { row ->
            val playing = row.postId == queue.postId
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp),
                        ).padding(4.dp),
            ) {
                if (playing) Text(stringResource(R.string.action_now_playing), style = MaterialTheme.typography.labelMedium)
                PlaylistRowView(
                    row = row,
                    buttonLabel = stringResource(R.string.action_done),
                    buttonDescription = stringResource(R.string.playlist_done_row, row.title),
                    onClick = { playlist.done(row) },
                )
            }
        }
        if (state.recent.isNotEmpty()) {
            item { Text(stringResource(R.string.playlist_recent), style = MaterialTheme.typography.titleMedium) }
            items(state.recent, key = { "recent-" + it.postId }) { row ->
                PlaylistRowView(
                    row = row,
                    buttonLabel = stringResource(R.string.action_undo),
                    buttonDescription = stringResource(R.string.playlist_undo_row, row.title),
                    onClick = { playlist.undone(row.postId) },
                )
            }
        }
    }
}
