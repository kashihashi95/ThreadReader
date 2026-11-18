package com.redder.deck

import kotlinx.coroutines.flow.StateFlow

/** One row of the playlist. [state] is new, prepared or started, or finished, skipped or done in the recent list. */
data class PlaylistRow(
    val postId: String,
    val sub: String,
    val title: String,
    val permalink: String,
    val state: String,
    val lengthMs: Long?,
    val positionChunk: Int?,
)

data class StaleSub(
    val sub: String,
    val lastOk: Long?,
)

/**
 * @property offline True when the last attempt to refresh failed; [rows] are then the last known.
 * @property loaded False until the list has been read once.
 */
data class PlaylistUiState(
    val rows: List<PlaylistRow> = emptyList(),
    val recent: List<PlaylistRow> = emptyList(),
    val stale: List<StaleSub> = emptyList(),
    val offline: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * What the card deck and the dashboard card need from a playlist, kept on the phone
 * (ADR-009) and given through `CurrentFlavor.playlistSource`.
 */
interface PlaylistSource {
    val state: StateFlow<PlaylistUiState>

    fun visibleRows(state: PlaylistUiState = this.state.value): List<PlaylistRow>

    /** Brings the list up to date. False when that failed; being unreachable is normal. */
    suspend fun syncOnce(): Boolean

    fun done(row: PlaylistRow)

    fun undone(postId: String)

    /** Runs for as long as the card deck is on screen. */
    suspend fun whileScreenVisible() = Unit
}
