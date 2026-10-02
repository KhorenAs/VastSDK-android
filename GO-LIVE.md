# Going live

What this SDK still needs before it serves real ads, ordered by what blocks the
road rather than by effort. The plan lives in the
[issues](https://github.com/KhorenAs/VastSDK-android/issues); this is the view of
it that matters on release day.

State: `./gradlew build` passes 203 tests — 169 in `vast-core`, ported from
VastSDK-apple against the same fixtures, and 34 in `vast-kit`, most of them whole
breaks in a real ExoPlayer under Robolectric — with no lint findings. The demo
has been run on an API 36 phone emulator and an API 36 Android TV emulator,
including a minified release build.

## Blockers

**1. Publishing.** The libraries publish to Maven local as
`com.kinodaran.vast:vast-core|vast-kit|vast-compose:0.1.0`, with sources. Where
they go for hosts is a decision, not a task: Maven Central needs a namespace and
signing keys; JitPack builds from a GitHub tag with no setup but publishes under
`com.github.KhorenAs`; GitHub Packages needs a token on every host. Tagging
`v0.1.0` follows from that choice. (#24)

**2. Real devices.** Emulators answer the logic. They do not answer what
differs between manufacturers — background limits, audio focus, Picture in
Picture, how a remote's keys arrive. Pixel, Samsung and Xiaomi, API 24 to 37. (#25)

## Blockers only for programmatic demand

**3. No Open Measurement adapter.** `VastMeasurement` is the seam and it is
deliberately empty, so every vendor hears `verificationNotExecuted`. The honest
answer, and incompatible with selling against a viewability contract. Needs the
IAB OM SDK licence first; open on Apple platforms too. (#26)

## Important, not blocking

**4. Process death.** A break interrupted by the process being killed is not
restored. The host builds a new session and player when the app comes back, the
tag is requested again, and whatever the ad server returns plays from the start
as a new ad with an impression of its own. That is the honest failure — restoring
a half-watched creative would resume an impression that already went out — but
it should be agreed with the ad server's counting and written down. (#14)

**5. A shared conformance suite.** Parity with the Apple SDK is proved by the
ported tests. Golden files exported from the Apple Harness would prove it
byte for byte, and fail the moment either side changes behaviour; that needs a
small change in VastSDK-apple. (#3, #11)

**6. Static checks in CI.** detekt and the binary compatibility validator, so a
public API change shows up in the diff. (#2)

**7. The AdChoices icon is parsed but not drawn.** As on Apple platforms. (#27)

**8. A player that is not Media3.** `VastAdsLoader` is the only player path; a
host on another player needs the standalone adapter. (#28)
