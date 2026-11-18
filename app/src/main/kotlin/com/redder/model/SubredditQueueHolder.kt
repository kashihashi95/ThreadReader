package com.redder.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @property active True while the audio service is working through a subreddit queue.
 * @property subreddit Sub of the post being read, or null between posts.
 * @property title Title of the post being read, or null between posts.
 * @property message Why the last queue ended on its own, for the dashboard; null otherwise.
 */
data class SubredditQueueState(
    val active: Boolean = false,
    val subreddit: String? = null,
    val title: String? = null,
    val postId: String? = null,
    val message: String? = null,
)

/** Process-wide view of the audio service's subreddit queue, for the dashboard and the HUD. */
object SubredditQueueHolder {
    private val _state = MutableStateFlow(SubredditQueueState())
    val state: StateFlow<SubredditQueueState> = _state.asStateFlow()

    fun update(state: SubredditQueueState) {
        _state.value = state
    }
}
