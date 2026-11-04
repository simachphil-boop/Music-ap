package app.vesper.player.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SleepTimerState(
    val endsAtMillis: Long = 0L,
    val endOfTrack: Boolean = false,
) {
    val active: Boolean get() = endsAtMillis > 0L || endOfTrack
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (endsAtMillis - now).coerceAtLeast(0L)
}

/** App-scoped sleep timer: fades the music out and pauses when time is up. */
class SleepTimer(
    private val scope: CoroutineScope,
    private val player: PlayerConnection,
) {
    private var job: Job? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    init {
        scope.launch {
            // Mirror "stop after current track" completion back into timer state.
            player.state.collect { s ->
                if (_state.value.endOfTrack && !s.stopAfterCurrent) _state.value = SleepTimerState()
            }
        }
    }

    fun start(minutes: Int) {
        cancel()
        val ms = minutes * 60_000L
        _state.value = SleepTimerState(endsAtMillis = System.currentTimeMillis() + ms)
        job = scope.launch {
            delay(ms)
            player.fadeOutAndPause()
            _state.value = SleepTimerState()
        }
    }

    fun startEndOfTrack() {
        cancel()
        player.setStopAfterCurrent(true)
        _state.value = SleepTimerState(endOfTrack = true)
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value.endOfTrack) player.setStopAfterCurrent(false)
        _state.value = SleepTimerState()
    }
}
