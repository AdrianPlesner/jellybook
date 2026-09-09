package dk.azp.jellybook.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

enum class SleepTimerMode { OFF, DURATION, CHAPTER_END }

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val endsAtEpochMs: Long? = null,
    val stopAtPositionMs: Long? = null,
    val label: String? = null,
) {
    val isActive: Boolean get() = mode != SleepTimerMode.OFF

    companion object {
        val OFF = SleepTimerState()

        fun forMinutes(minutes: Int): SleepTimerState =
            SleepTimerState(SleepTimerMode.DURATION, endsAtEpochMs = System.currentTimeMillis() + minutes * 60_000L, label = "$minutes min")

        fun untilPosition(positionMs: Long, label: String): SleepTimerState =
            SleepTimerState(SleepTimerMode.CHAPTER_END, stopAtPositionMs = positionMs, label = label)
    }
}

/** Custom session commands and the bundle layout shared between the UI controller and the playback service. */
object PlayerCommands {

    const val ACTION_SET_SLEEP_TIMER = "dk.azp.jellybook.SET_SLEEP_TIMER"

    val SET_SLEEP_TIMER = SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)

    private const val EXTRA_MODE = "sleep_timer_mode"
    private const val EXTRA_ENDS_AT = "sleep_timer_ends_at"
    private const val EXTRA_STOP_AT = "sleep_timer_stop_at"
    private const val EXTRA_LABEL = "sleep_timer_label"

    fun sleepTimerExtras(state: SleepTimerState): Bundle = Bundle().apply {
        putString(EXTRA_MODE, state.mode.name)
        state.endsAtEpochMs?.let { putLong(EXTRA_ENDS_AT, it) }
        state.stopAtPositionMs?.let { putLong(EXTRA_STOP_AT, it) }
        putString(EXTRA_LABEL, state.label)
    }

    fun sleepTimerState(bundle: Bundle?): SleepTimerState {
        val mode = bundle?.getString(EXTRA_MODE)?.let { name -> SleepTimerMode.entries.firstOrNull { it.name == name } } ?: SleepTimerMode.OFF
        return SleepTimerState(
            mode = mode,
            endsAtEpochMs = if (bundle != null && bundle.containsKey(EXTRA_ENDS_AT)) bundle.getLong(EXTRA_ENDS_AT) else null,
            stopAtPositionMs = if (bundle != null && bundle.containsKey(EXTRA_STOP_AT)) bundle.getLong(EXTRA_STOP_AT) else null,
            label = bundle?.getString(EXTRA_LABEL),
        )
    }
}
