# VastSDK for Android

A native VAST 4.3 linear-video ad SDK for Android. No Google IMA, no VPAID, no
WebView. It is the Android counterpart of
[VastSDK-apple](https://github.com/KhorenAs/VastSDK-apple) and matches its
behaviour: the two share the specification, the test fixtures and every
behavioural decision, and a conformance suite built from the Apple SDK's own
output proves it (#3).

**Status: in development.** The plan is tracked as
[issues and milestones](https://github.com/KhorenAs/VastSDK-android/milestones).

## Modules

```
vast-core     pure Kotlin/JVM: parser, wrapper chains, macros, tracking — no android.*
vast-kit      session, Media3 playback, beacon delivery, lifecycle — no UI
vast-compose  the ad surface, in Compose
demo          Compose app with the same scenarios as the Apple demos
```

Playback goes through a `VastAdPlayer` interface, the same split Google IMA makes
with `VideoAdPlayer`. The one implementation is `VastAdsLoader`, a Media3
`AdsLoader`, so a break is inserted into the host's own player timeline rather
than played on a player of the SDK's.

## Requirements

Android 7.0 (API 24) · Media3 · Jetpack Compose · Kotlin 2.4

## Building

```bash
./gradlew build      # all modules, unit tests and lint
./gradlew :vast-core:test
```

Android Studio's bundled JDK is enough; point `JAVA_HOME` at it when building
from a terminal:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## License

MIT — see [LICENSE](LICENSE).
