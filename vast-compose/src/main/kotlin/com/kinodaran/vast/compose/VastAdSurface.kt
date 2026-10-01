package com.kinodaran.vast.compose

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kinodaran.vast.kit.R
import com.kinodaran.vast.kit.VastAdPosition
import com.kinodaran.vast.kit.VastAdSession
import com.kinodaran.vast.kit.VastAdState
import com.kinodaran.vast.kit.VastClickPresentation
import com.kinodaran.vast.kit.VastSkipPresentation
import kotlin.math.ceil

/**
 * The ad UI, composed over the host's own player:
 *
 * ```kotlin
 * Box {
 *     PlayerSurface(player)
 *     VastAdSurface(session)
 * }
 * ```
 *
 * In the SDK rather than in every host because two of the things it draws are
 * specification requirements, not decoration: a skippable ad must be offered a
 * skip control (§2.3), and the ad must be clickable (§3.10.1). Leaving those to
 * each host would mean the SDK could not tell whether it was compliant. Every
 * element is still replaceable through its slot, so owning the behaviour does
 * not mean owning the look — and the behaviour lives in the session, so a slot
 * only ever draws.
 *
 * The surface raises its own `zIndex` ([DEFAULT_Z_INDEX]), so declaration order
 * in the host's `Box` cannot bury the skip control by accident.
 *
 * @param skipButton replaces the skip control. Its argument is the time left
 *   until the control unlocks, or `null` once it has. The SDK wraps it in a
 *   focusable, clickable button, so a replaced control stays reachable with a
 *   D-pad and by accessibility services — and its drawn size is checked, so one
 *   that draws nothing is reported rather than silently unskippable.
 * @param onClickThrough handles the destination yourself — a Custom Tab, say —
 *   instead of letting the SDK open it with `ACTION_VIEW`.
 * @param zIndex where the surface sits among its siblings. Lowering it below
 *   another view means the skip control can be covered, and §2.3 stops being
 *   something the SDK can vouch for.
 */
@Composable
public fun VastAdSurface(
    session: VastAdSession,
    modifier: Modifier = Modifier,
    skipButton: (@Composable (timeUntilSkip: Double?) -> Unit)? = null,
    adBadge: (@Composable (position: VastAdPosition) -> Unit)? = null,
    countdown: (@Composable (remainingSeconds: Double) -> Unit)? = null,
    onClickThrough: ((String) -> Unit)? = null,
    zIndex: Float = DEFAULT_Z_INDEX,
) {
    val state by session.state.collectAsState()
    val ad by session.currentAd.collectAsState()
    val hiddenUi by session.isHiddenUi.collectAsState()
    val inPictureInPicture by session.isInPictureInPicture.collectAsState()
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val isTelevision = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    // The response asked for host-drawn UI and the host allowed it, so this
    // surface draws nothing and takes no taps — the host's own controls are the
    // only ones there.
    val suppressed = hiddenUi && ad?.isUiHidden == true
    val isShowingAd = !suppressed && ad != null && (state == VastAdState.Playing || state == VastAdState.Paused)

    // Measured from the outside, always: the controls only exist while an ad is
    // on screen, and the surface has to know how much room it was given either way.
    Box(
        modifier
            .fillMaxSize()
            .zIndex(zIndex)
            .onSizeChanged { session.noteSurfaceSize(it.width, it.height, density) },
    ) {
        val current = ad ?: return@Box
        if (!isShowingAd) return@Box

        // §3.10.1's model: the ad itself is what the viewer clicks. Not on a TV,
        // where a transparent layer under a D-pad can never take focus — an inert
        // click path that looks present is worse than an absent one, because nobody
        // finds out. The session reports it instead.
        //
        // Nor in the Picture in Picture window, which shows the app but takes no
        // touches: the badge and countdown stay, because the viewer is still owed
        // being told it is an ad, and the controls go, because there they would
        // only look reachable.
        val interactive = !inPictureInPicture
        if (interactive && !isTelevision && session.configuration.clickPresentation == VastClickPresentation.SURFACE && current.linear.clickThrough != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        val destination = session.click() ?: return@clickable
                        onClickThrough?.invoke(destination) ?: open(context, destination)
                    },
            )
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(session, adBadge)
                Countdown(session, countdown)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Drawn only when the SDK owns the control. Under HOST the host draws
                // it, and under UNSUPPORTED a skippable ad never reaches playback.
                if (interactive && session.effectiveSkipPresentation == VastSkipPresentation.SDK && current.isSkippable) {
                    Skip(session, skipButton, density, isTelevision)
                }
            }
        }
    }
}

/**
 * High enough that ordinary layout cannot end up on top of it, so a host does not
 * have to remember to declare the surface last. Finite on purpose: a host with a
 * real reason to cover the ad — a modal error, a paywall — can go higher
 * deliberately. What this prevents is covering it by accident.
 */
public const val DEFAULT_Z_INDEX: Float = 10_000f

/**
 * White with a soft shadow: the label sits on the creative itself, and a white
 * label over a white frame — a Google Ad Manager slate, say — is no label at all.
 */
private val Label = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(color = Color(0xB3000000), offset = Offset(0f, 1f), blurRadius = 4f),
)

@Composable
private fun Badge(session: VastAdSession, slot: (@Composable (VastAdPosition) -> Unit)?) {
    val position by session.adPosition.collectAsState()
    if (slot != null) return slot(position)
    val text = if (position.isPod) {
        stringResource(R.string.vast_ad_badge_pod, position.index, position.total)
    } else {
        stringResource(R.string.vast_ad_badge)
    }
    BasicText(
        text,
        Modifier.background(Color(0xFFFFCC00), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        style = Label.copy(color = Color.Black, shadow = null),
    )
}

@Composable
private fun Countdown(session: VastAdSession, slot: (@Composable (Double) -> Unit)?) {
    val remaining by session.remainingTime.collectAsState()
    if (slot != null) return slot(remaining)
    BasicText(stringResource(R.string.vast_ad_countdown, ceil(remaining).toInt()), style = Label)
}

@Composable
private fun Skip(session: VastAdSession, slot: (@Composable (Double?) -> Unit)?, density: Float, isTelevision: Boolean) {
    val canSkip by session.canSkip.collectAsState()
    val timeUntilSkip by session.timeUntilSkip.collectAsState()
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // A D-pad has no pointer to move: the control that just became usable is the
    // one the viewer is most likely to want, so it takes focus by itself.
    LaunchedEffect(canSkip, isTelevision) {
        if (canSkip && isTelevision) runCatching { focus.requestFocus() }
    }

    // A real button whatever is drawn inside it — focusable, clickable, announced
    // as a button — and measured, so a slot that draws nothing is caught.
    val control = Modifier
        .focusRequester(focus)
        .onSizeChanged { session.noteSkipControlSize(it.width, it.height, density) }
        .clickable(
            enabled = canSkip,
            role = Role.Button,
            interactionSource = interaction,
            indication = null,
        ) { runCatching { session.skip() } }
        .focusable(enabled = canSkip, interactionSource = interaction)

    if (slot != null) {
        Box(control) { slot(if (canSkip) null else timeUntilSkip) }
        return
    }

    if (canSkip) {
        Box(
            control
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .background(if (focused) Color.White else Color(0xE6FFFFFF), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(stringResource(R.string.vast_skip_action), style = Label.copy(color = Color.Black, fontSize = 14.sp, shadow = null))
        }
    } else {
        val remaining = timeUntilSkip ?: return
        BasicText(
            stringResource(R.string.vast_skip_countdown, ceil(remaining).toInt()),
            Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
            style = Label,
        )
    }
}

/** The default way out to the advertiser: whatever on the device handles the URL. */
private fun open(context: Context, destination: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the device opens it. The click was still reported.
    }
}
