# VastSDK for Android

A native VAST 4.3 linear-video ad SDK for Android phones, tablets and TVs. No
Google IMA, no VPAID, no WebView.

It is the Android counterpart of [VastSDK-apple](https://github.com/KhorenAs/VastSDK-apple)
and behaves the same way: the two share the specification, the test fixtures
and every behavioural decision, and every VASTCoreTests suite of the Apple SDK is
ported here against the same files. Where Android needs something Apple
platforms do not — or the reverse — this README says so.

4.3 is what it implements; 2.0, 3.0 and every 4.x **parse through the same
version-tolerant path**, because real ad servers still send them. An element the
SDK does not know is skipped rather than rejected; the only document it refuses
outright is a pre-3.0 one, which gets VAST error 102.

## Modules

```
vast-core     pure Kotlin/JVM: parser, wrapper chains, macros, tracking — no android.*
vast-kit      session, Media3 ad insertion, beacon delivery, lifecycle — no UI
vast-compose  the ad surface, in Compose
demo          phone and TV app with the Apple demos' scenarios
```

```kotlin
dependencies {
    implementation("com.kinodaran.vast:vast-compose:0.1.0") // brings vast-kit and vast-core
}
```

Requirements: Android 7.0 (API 24) · Media3 1.11 · Jetpack Compose · Kotlin 2.4.
The SDK brings no Google Play services and no HTTP client of its own.

## Playing a break

The break goes into the host's own Media3 player, the way `media3-exoplayer-ima`
puts an IMA break there: `VastAdsLoader` is a Media3 `AdsLoader`, and the content
`MediaItem` names the ad tag.

```kotlin
val session = VastAdSession(context)

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(context).withVastAds(session))
    .build()
session.adsLoader.setPlayer(player)

player.setMediaItem(
    MediaItem.Builder()
        .setUri(contentUri)
        .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(adTagUri).build())
        .build(),
)
player.prepare()
player.play()
```

The tag is a pre-roll. It is resolved when the content is prepared — Wrapper
chain and all — and the content waits for it, no longer than
`resolutionTimeoutSeconds`. Media3 then plays the pod in front of the content and
returns to it; the session reports impression, quartiles, progress, skip, click
and errors, with the §6 macros expanded.

A response already in hand goes in as a `data:` URI:

```kotlin
.setAdsConfiguration(MediaItem.AdsConfiguration.Builder(VastAdsLoader.adTagUriForResponse(xml)).build())
```

**Keep one session and one player for the life of the screen** — across
configuration changes too, in a `ViewModel` or an activity that handles its own.
Rebuilt on rotation, they restart the break and send its impression twice.

### Breaks on demand

```kotlin
session.insertBreak(adTagUri)
```

Plays a break during the content: right away when the content is playing, after
the break on screen when one is. Several asked for at once play in order;
`pendingBreakCount` says how many are waiting. The content stops where it was and
resumes from there. Content the player cannot seek in — a live stream without a
window — holds an inserted break until the viewer next seeks, which is Media3's
rule.

## The ad UI

```kotlin
Box {
    ContentFrame(player)          // media3-ui-compose
    VastAdSurface(session)
}
```

The surface draws the badge, the countdown, the skip control and the click
layer. Two of those are requirements, not decoration: a skippable ad must be
offered a skip control (§2.3), and the ad must be clickable (§3.10.1). Every
element is still replaceable:

```kotlin
VastAdSurface(
    session,
    skipButton = { timeUntilSkip -> MySkip(timeUntilSkip) },
    adBadge = { position -> MyBadge(position) },
    countdown = { remaining -> MyCountdown(remaining) },
    onClickThrough = { url -> openInCustomTab(url) },
)
```

A replaced skip control is still wrapped in a focusable, clickable button and
measured, so one that draws nothing is reported through
`onSkipControlUnavailable` rather than silently leaving a skippable ad
unskippable. The surface raises its own `zIndex`, so a sibling declared after it
cannot bury the control by accident. Strings ship in English and Armenian.

Who draws the skip control is `skipPresentation`: `SDK` (the default), `HOST` —
the host reads `canSkip` and `timeUntilSkip` and calls `skip()` — or `UNSUPPORTED`,
which refuses a skippable ad with error 200 rather than playing it without one.
A response carrying the `uiSettings` UI-hidden key hands the whole UI to the host,
but only when the host has set `isHiddenUi`: a vendor key cannot take the §2.3
promise away from a host that never agreed to keep it.

## What the session holds the line on

- **No seeking, no speed-up.** Media3 refuses seeks while an ad plays. The
  session plays the creative at 1× and gives the content its own speed back.
- **Nothing unwatched.** Leaving the app holds the ad and coming back resumes it
  — not reported as a pause, because nobody paused. Media3 keeps decoding in the
  background; an ad behind the home screen would otherwise earn quartiles.
- **The system's media controls.** Give the host's `MediaSession` the session's
  wrapper, and the notification, the lock screen, a headset and a TV remote
  cannot skip to the next item or change speed during the break, and name the ad
  while it plays:

  ```kotlin
  MediaSession.Builder(context, session.forMediaSession(player)).build()
  ```

- **Picture in Picture.** `pictureInPicture` is `ALLOWED` (the default),
  `PAUSES_AD` or `SUSPENDED`. Report the window with
  `session.observePictureInPicture(activity)`, and gate the host's own way in —
  its button and `setAutoEnterEnabled` — on `permitsPictureInPicture`. Android's
  window shows the whole activity but takes no touches, so the surface keeps the
  badge and countdown there and drops the skip control and click layer. Not
  every device has the window — most televisions do not — so offer it only where
  `PackageManager.FEATURE_PICTURE_IN_PICTURE` is present.

## Television

On a leanback device the surface does not draw a click layer — a transparent
layer under a D-pad can never take focus — and the session says so through
`onClickThroughUnavailable` if the host left `clickPresentation` on `SURFACE`. Use
`HOST` with a focusable control of your own, or `DISABLED` to opt out knowingly.
The skip control takes focus when it unlocks and draws a ring that reads from
across a room; a remote's play and pause reach the ad through the media session.

## Knowing what was reported

```kotlin
session.listener = object : VastAdSessionListener {
    override fun onReported(kind: VastBeacon.Kind, ad: VastAd?) { /* … */ }
}
```

Called once per distinct kind, in the order the beacons went out, whether or not
the network accepted them. A response with three `<Impression>` URLs is three
beacons and one impression. The state is also published as flows — `state`,
`currentAd`, `adPosition`, `remainingTime`, `canSkip`, `timeUntilSkip`.

## Measurement

`session.measurement` is where an Open Measurement integration plugs in; the SDK
parses `<AdVerifications>` and never executes them. With nothing configured,
every vendor that asked is told `verificationNotExecuted` and every
`<ViewableImpression>` hears `<ViewUndetermined>`, because that is the truth.

## Delivery and privacy

Beacons go out over `HttpURLConnection`: a 5xx is retried, a 4xx is not, and what
fails is kept in an app-private retry queue and sent again on a later flush,
across launches. A host with its own HTTP stack supplies a `VastBeaconTransport`.
The SDK collects nothing of its own; [PRIVACY.md](PRIVACY.md) has what leaves the
device, what is stored, and what to put in the Data safety form.

## Building

```bash
./gradlew build      # every module: unit tests, lint, detekt, vast-core's API check
./gradlew :vast-core:test
./gradlew :vast-core:updateKotlinAbi   # after a deliberate public API change
./gradlew publishToMavenLocal
```

detekt's settings are in [config/detekt/detekt.yml](config/detekt/detekt.yml), which
lists only what differs from detekt's defaults, each with its reason. A rule that
does not fit one place is suppressed there, with the reason beside it, rather than
switched off for every file.

Android Studio's bundled JDK is enough; point `JAVA_HOME` at it when building
from a terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

The `vast-kit` tests run whole breaks in a real ExoPlayer under Robolectric.
[GO-LIVE.md](GO-LIVE.md) lists what is still open before this serves real ads.

## Known issues

**Media3 1.11.1 holds a released ExoPlayer for its stuck-playing timeout** — 10
seconds on a device, 30 on an emulator — and with it everything the player
references, the session among them. `StuckPlayerDetector.release()` clears its
messages before removing its listener, and removing a listener delivers the
events still pending, which posts a fresh timeout. It lets go on its own. A
LeakCanary run that watches a released session sooner than that reports it as
retained, so the demo's debug build waits the timeout out first.

## License

MIT — see [LICENSE](LICENSE).
