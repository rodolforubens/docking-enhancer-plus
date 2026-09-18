# Publishing a release

The release workflow runs on standard Ubuntu GitHub-hosted runners when a tag such as
`v0.2.0` is pushed. It checks the version and native asset checksum, runs JVM unit tests,
builds and verifies the signed APK, and publishes it with a checksum and generated notes.
It uses the committed native binary. Hardware e2e tests still need to pass on the handheld
before publishing changes to the daemon or hide/restore paths.

## One-time signing setup

Configure these repository Actions secrets using the same key as the local release build:

Authenticate the GitHub CLI as the fork owner, then run
`python3 scripts/configure-release-secrets.py`. The script reads the local signing files
and passes the values to GitHub through standard input without displaying them.

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 encoding of `android/signing/docking-enhancer-release.jks` |
| `RELEASE_STORE_PASSWORD` | `storePassword` from local `android/keystore.properties` |
| `RELEASE_KEY_ALIAS` | `keyAlias` from local `android/keystore.properties` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` from local `android/keystore.properties` |

Keep an independent secure backup of the keystore and credentials. Never commit them.
Future updates must use the same signing key. Signing files on the runner are removed
even when a build fails. The workflow is triggered by tags or a manual dispatch, not pull requests.

## Publish

1. Increment `versionCode` and set `versionName` in `android/app/build.gradle`.
2. Complete the required device validation and commit the release changes.
3. Create and push the matching tag with the commit:

   ```sh
   git tag -a v0.2.0 -m 'Docking Enhancer 0.2.0'
   git push --atomic origin main v0.2.0
   ```

4. Check the **Publish Android release** Actions run and download the APK from Releases.

## Retry with an updated workflow

Commit and push workflow fixes to `main`. In **Actions → Publish Android release → Run
workflow**, choose `main` and enter the existing release tag, for example `v0.2.0`.
Alternatively, run:

```sh
gh workflow run release.yml --repo rodolforubens/docking-enhancer-plus --ref main -f tag=v0.2.0
```

The workflow definition comes from `main`, while the app source is checked out from
the specified tag. This allows fixing the release automation without moving the tag
or changing the app version. The tag must already exist and match `versionName`.
The manual trigger becomes available once this workflow is on the default branch.
The **Re-run jobs** button on an old run continues to use that run's original workflow.

An existing release is reused on a rerun, preserving its edited notes. If the release
has immutable assets enabled, a published asset cannot be replaced; publish a new version.
