# Publishing

A tag `v<version>` on `main` is a release. The [Release workflow](.github/workflows/release.yml)
builds and tests the tag, checks that the tag names the version the build
declares, and then publishes it:

| Where | Coordinates | Needs |
|---|---|---|
| GitHub Packages | `com.kinodaran.vast:<module>:<version>` | nothing: the workflow's own token |
| JitPack | `com.github.KhorenAs.VastSDK-android:<module>:v<version>` | nothing: the workflow asks JitPack to build the tag |
| Maven Central | `com.kinodaran.vast:<module>:<version>` | the one-time setup below |

When all of them succeed, the workflow creates the GitHub release with notes
generated from the merged commits.

## Releasing

1. Set the version in the root `build.gradle.kts`.
2. Commit, push, and let CI pass.
3. Tag and push the tag:

   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```

4. On Maven Central, open the deployment in the [Central Portal](https://central.sonatype.com/publishing/deployments)
   and press **Publish**. The workflow uploads and validates; it does not publish
   by itself, because nothing published to Maven Central can be taken back.

A version is published once. GitHub Packages and Maven Central both refuse the
same version a second time, so a fix is a new version, not a moved tag.

## Maven Central: the one-time setup

The account, the domain and the keys are the owner's, so these steps are too.

1. **Account.** Sign in at [central.sonatype.com](https://central.sonatype.com).
2. **Namespace.** Under *Namespaces*, add `com.kinodaran`. The portal gives a
   verification key; add it to kinodaran.com as a DNS `TXT` record, and press
   *Verify*. The record can go once the namespace shows as verified.
3. **Token.** Under *Account*, *Generate User Token*. It shows a username and a
   password, once.
4. **Signing key.** Maven Central only takes signed artifacts, checked against
   a public key server:

   ```bash
   gpg --quick-generate-key "Kinodaran VastSDK" ed25519 sign 3y
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   gpg --armor --export-secret-keys <key id>
   ```

5. **Secrets.** In the repository's *Settings → Secrets and variables → Actions*:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | the token's username |
   | `MAVEN_CENTRAL_PASSWORD` | the token's password |
   | `SIGNING_KEY` | the exported private key, `-----BEGIN PGP PRIVATE KEY BLOCK-----` and all |
   | `SIGNING_KEY_PASSWORD` | the key's passphrase |

6. **The releases before it.** Run the Release workflow from the *Actions* tab
   with the tag, `v0.1.0` say, and it publishes that tag to Maven Central alone.

Until the secrets exist, the Maven Central job says it was skipped and the rest
of the release goes ahead.
