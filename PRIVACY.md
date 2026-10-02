# Privacy

What VastSDK for Android does with data, for the developer filling in Google
Play's Data safety form and for anyone reviewing what an ad break sends.

The short answer: **the SDK collects nothing of its own.** It reads no device
identifier, keeps no profile, and sends no analytics anywhere. What leaves the
device goes to the URLs the ad server put in its own response, and carries the
values the host chose to supply — nothing the SDK found out by itself.

## What leaves the device, and to whom

| Request | Sent to | Carries |
|---|---|---|
| The ad tag | The URL the host put in the `AdsConfiguration`, then each `VASTAdTagURI` the responses redirect to | Whatever the host put in the URL |
| Tracking beacons — impression, quartiles, clicks, errors | The URIs in the response's `<Impression>`, `<Tracking>`, `<Error>`, `<ClickTracking>` and the like | The §6 macros the ad server asked for (below) |
| The creative | The `<MediaFile>` URL the SDK chose | Nothing beyond the request itself |

Every one of these goes to a server the ad response names. The SDK adds no
endpoint of its own — there is no Kinodaran server in the path unless the ad
server is one.

## Macros

A tracking URI can ask for values by macro (VAST 4.3 §6). The SDK answers the
ones it can know without asking anyone, and leaves the rest to the host:

- **Answered by the SDK:** playhead, the creative's URL and MIME type, player
  size, muted and fullscreen state, the app's package name (`[APPBUNDLE]`), the
  SDK's own name and version (`[CLIENTUA]`), a per-break transaction ID, a cache
  buster, the time.
- **Only ever what the host supplies,** through `VastConfiguration.macroValues`:
  the advertising ID (`[IFA]`, `[IFATYPE]`), limit-ad-tracking (`[LIMITADTRACKING]`),
  the TCF consent string (`[GDPRCONSENT]`), which regulations apply
  (`[REGULATIONS]`), the device user agent, and what the content is.

An unsupplied value goes out as `-1`, the specification's "unknown". The SDK
never fills one in by guessing.

## The advertising ID

The SDK never reads it. There is no Google Play services dependency in the
library — `vast-core`, `vast-kit` and `vast-compose` bring in none — and no call
to `AdvertisingIdClient` anywhere in it.

A host that wants exchanges to see one passes it in `macroValues`, and should do
so only with the viewer's consent and when limit-ad-tracking is off. Leave it
`null` otherwise; never send a zeroed identifier in its place.

## Consent

The TCF consent string from the host's CMP goes into `[GDPRCONSENT]` exactly as
it was given — it is already base64url, and encoding it again would corrupt it.
`[REGULATIONS]` names the regimes that apply (`gdpr`, `coppa`). Deciding either is
the host's: the SDK has no way to know where the viewer is or what they agreed to.

## What is stored on the device

One file, and only for retries: beacons that could not be delivered, held in
`noBackupFilesDir/vast-pending-beacons.tsv` and sent again on a later flush.

- **Contents:** each failed beacon's URL with its macros already expanded, so it
  can carry the advertising ID and consent string if the host supplied them, and
  the time it was recorded.
- **Limits:** at most 500 entries, oldest dropped first, and nothing older than
  six hours.
- **Protection:** app-private storage, never included in device backups, and
  encrypted at rest by the platform on devices that ship with Android 10 or
  later. Removed with the app.

A host that counts delivery some other way can construct `VastHttpTransport`
with no queue, and nothing is stored at all.

## Network security

No cleartext is required. The SDK prefers an `https` rendition whenever a
response offers one, and the default network security configuration — which
refuses cleartext from API 28 — needs no change for it. A response that only
offers `http` URLs will have them refused by the platform unless the host
allows cleartext for those domains, and that is the host's decision to make.

## Data safety form

What to declare depends on what the host passes in, not on the SDK:

- **Device or other IDs — collected, shared, for advertising.** Only if the host
  supplies the advertising ID through `macroValues`. The SDK alone collects none.
- **App activity: app interactions — shared, for advertising and analytics.** The
  beacons tell the ad server that an ad was shown, how far it played, and whether
  it was clicked or skipped.
- **Data is encrypted in transit:** yes for every `https` URL the ad server uses;
  the SDK uses `https` wherever the response allows it.
- **Data can be deleted:** the retry queue is removed with the app and expires on
  its own after six hours.

The ad server is a third party to the app in Google Play's terms unless the
host operates it; its own privacy policy covers what it does with the beacons.
