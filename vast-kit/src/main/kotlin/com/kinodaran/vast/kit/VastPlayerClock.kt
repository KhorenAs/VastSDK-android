package com.kinodaran.vast.kit

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import com.kinodaran.vast.core.VastTick
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Default [VastClock]: samples the Media3 player while one creative plays.
 *
 * Also stamps `itemIsOurs`, which is how the tracking engine learns that the
 * player is no longer on this creative — the one situation where nothing after
 * this moment can be attributed to it.
 *
 * Scheduled on the player's own [Clock] and application thread, the only thread
 * a Media3 player may be read from. The same clock that moves the playhead
 * timestamps the tick, so the engine's comparison of "how far did the playhead
 * move" against "how much time passed" compares one clock with itself — which is
 * also what makes it exact under Media3's fake clock in tests.
 */
@OptIn(UnstableApi::class)
internal class VastPlayerClock(
    private val player: Player,
    /** Whether the player is on this clock's creative right now. */
    private val isOurs: (Player) -> Boolean,
    private val clock: Clock,
    /** 200 ms keeps quartile timing within a frame or two without waking the CPU on every frame. */
    private val intervalMillis: Long = 200,
) : VastClock {

    @Volatile
    private var stopped = false

    override fun ticks(): Flow<VastTick> = callbackFlow {
        stopped = false
        val handler = clock.createHandler(player.applicationLooper, null)
        val step = object : Runnable {
            override fun run() {
                if (stopped) {
                    close()
                    return
                }
                trySend(tick())
                handler.postDelayed(this, intervalMillis)
            }
        }
        step.run()
        awaitClose { handler.removeCallbacksAndMessages(null) }
    }

    override fun stop() {
        stopped = true
    }

    private fun tick(): VastTick {
        val ours = isOurs(player)
        val duration = player.duration
        return VastTick(
            adTime = if (ours) player.currentPosition / 1000.0 else 0.0,
            duration = if (ours && duration != C.TIME_UNSET && duration > 0) duration / 1000.0 else null,
            rate = if (player.isPlaying) player.playbackParameters.speed else 0f,
            // Monotonic, so a system clock change cannot be mistaken for progress.
            wallClock = clock.elapsedRealtime() / 1000.0,
            itemIsOurs = ours,
            isMuted = player.isCommandAvailable(Player.COMMAND_GET_VOLUME) && player.volume == 0f,
        )
    }
}
