# Going live

What this SDK still needs before it serves real ads, ordered by what blocks the
road rather than by effort. The plan lives in the
[issues](https://github.com/KhorenAs/VastSDK-android/issues); this is the view of
it that matters on release day.

State: `./gradlew build` passes 203 tests — 169 in `vast-core`, ported from
VastSDK-apple against the same fixtures, and 34 in `vast-kit`, most of them whole
breaks in a real ExoPlayer under Robolectric — with no lint or detekt findings. The demo
has been run on an API 36 phone emulator and an API 36 Android TV emulator,
including a minified release build. Its debug build hands what each closed player
screen released to LeakCanary, and 20 open and close cycles leave nothing behind.

## Blockers

**1. Maven Central.** A tag publishes to GitHub Packages and JitPack by itself.
Maven Central also needs the `com.kinodaran` namespace verified on kinodaran.com,
a signing key and four repository secrets — the owner's account, domain and keys,
so the owner's steps. [PUBLISHING.md](PUBLISHING.md) lists them. (#24)

**2. Real devices.** Emulators answer the logic. They do not answer what
differs between manufacturers — background limits, audio focus, Picture in
Picture, how a remote's keys arrive. Pixel, Samsung and Xiaomi, API 24 to 37. (#25)

## Blockers only for programmatic demand

**3. No Open Measurement adapter.** `VastMeasurement` is the seam and it is
deliberately empty, so every vendor hears `verificationNotExecuted`. The honest
answer, and incompatible with selling against a viewability contract. Needs the
IAB OM SDK licence first; open on Apple platforms too. (#26)

## Important, not blocking

**4. Process death.** Decided: a break interrupted by the process being killed
is not restored. The host builds a new session and player when the app comes
back, the tag is requested again, and whatever the ad server returns plays from
the start as a new ad with an impression of its own. Restoring a half-watched
creative would resume an impression that already went out, so the new request is
the honest failure. The ad server's reports see two impressions for that viewer,
and whoever reads them should know why. (#14)

**5. A shared conformance suite.** Parity with the Apple SDK is proved by the
ported tests. Golden files exported from the Apple Harness would prove it
byte for byte, and fail the moment either side changes behaviour; that needs a
small change in VastSDK-apple. (#3, #11)

**6. An API check for the Android modules.** `vast-core`'s public API is in
`vast-core/api/vast-core.api`, and a change to it fails the build until the dump
is updated. Kotlin's ABI validation does not yet support Android modules built
with AGP's built-in Kotlin, so `vast-kit` and `vast-compose` have no dump; their
API changes show up only in review. detekt runs on every module. (#2)

**7. The AdChoices icon is parsed but not drawn.** As on Apple platforms. (#27)

**8. A player that is not Media3.** `VastAdsLoader` is the only player path; a
host on another player needs the standalone adapter. (#28)
