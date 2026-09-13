# Publishing Duo on Google Play

The repository is ready. What is left needs your Play Console account, your
keystore, and a few decisions only you can make.

Everything below is listed in the order the Console asks for it.

---

## 1. Make a keystore, once

An Android app is signed with a key you keep. Google Play ties your listing to
that key forever. If you lose it you cannot update the app under the same
listing — unless Google Play App Signing is holding it for you.

```
keytool -genkeypair -v -keystore duo-release.jks \
  -alias duo -keyalg RSA -keysize 4096 -validity 10000
```

Then copy the example file and fill it in:

```
cp keystore.properties.example keystore.properties
```

**Back the keystore up somewhere you will still have in five years.** Put it in
a password manager, or a safe. `keystore.properties` and `*.jks` are already
ignored by Git, so neither can be committed by accident.

## 2. Turn on Play App Signing

Recommended for a first release. Google holds the real signing key and you sign
with an upload key. If you lose the upload key, they can replace it. Do this
**before** the first upload — you cannot move to it later without starting a new
listing.

## 3. Versions

The version comes from git, not from a number someone remembers to bump.

- A tag `v1.2.3` gives **versionName 1.2.3**
- The commit count gives **versionCode**
- Without a tag the version is `0.0.0-dev`

Google Play needs versionCode to go up on every upload, for ever. A number that
is never reused cannot be got wrong by forgetting to change it, which is the
usual way a release fails at the last moment.

To cut a release:

```
git tag v1.0.0
git push origin v1.0.0
```

That runs `.github/workflows/release.yml`, which builds a signed bundle and APK,
publishes both on the GitHub release page, and sends the bundle to the Play
**internal testing** track. Nobody outside your tester list sees it. Promoting it
to production stays a deliberate act in the Console.

## 4. Build a bundle by hand

```
./gradlew :app:bundleRelease
```

The file lands at `app/build/outputs/bundle/release/app-release.aab`. Play wants
an `.aab`, not an `.apk`.

To build it in CI instead, tag a commit:

```
git tag v1.0.0
git push origin v1.0.0
```

That runs `.github/workflows/release.yml`, which needs four repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i duo-release.jks \| pbcopy` and paste the result |
| `STORE_PASSWORD` | the store password you chose |
| `KEY_ALIAS` | `duo` |
| `KEY_PASSWORD` | the key password you chose |
| `PLAY_SERVICE_ACCOUNT_JSON` | optional. Without it the bundle is not sent to Play — see below. |

The workflow writes the keystore to the runner, builds, publishes the release,
and deletes the keystore afterwards.

**To let it upload to Play**, make a service account in the Play Console
(Setup → API access), give it access to this app, download its JSON key, and
paste the whole file into a repository secret called
`PLAY_SERVICE_ACCOUNT_JSON`. Without that secret the workflow says so and
skips the upload; nothing else changes.

## 5. Host the privacy policy

Play requires a privacy policy **URL**, not a file in your repository. The
policy is written and ready at [PRIVACY.md](PRIVACY.md).

The simplest way to get a URL is to turn on GitHub Pages for this repository
(Settings → Pages → deploy from `main`), which serves it at:

```
https://azhar9.github.io/android-duo/PRIVACY.md
```

Check that address opens in a browser before you paste it into the Console.

## 6. The store listing

| Field | Notes |
|---|---|
| App name | 30 characters at most. "Duo — two phones, one screen" fits. |
| Short description | 80 characters. Play shows this in search results. |
| Full description | 4000 characters. The README is a good source. |
| App icon | 512 × 512 PNG. `docs/icon.png` is 512 × 512 — resize if the store rejects it. |
| Feature graphic | 1024 × 500 PNG. **You have to make this one.** |
| Phone screenshots | At least 2, at least 320 px on the short side. **You have to take these.** Take them with the two phones actually working — that is the whole point of the app. |
| Category | Tools, or Video Players & Editors. |

## 7. The forms in the Console

**Data safety.** Duo collects nothing, shares nothing, and stores nothing on a
server. Answer every question "no" and say the data is not transmitted off the
device. The one thing to think about: in web mode the user's phone fetches a
page directly, which is the user's browser talking to that site, not your app
sending data. [PRIVACY.md](PRIVACY.md) already says this in plain words.

**Content rating.** Complete the questionnaire honestly. Duo plays whatever the
user chooses, and it has no content of its own. Expect a rating that reflects
user-provided content.

**Target audience.** Duo is not for children. Say so.

**Government apps.** Not one.

**News apps.** Not one.

**Permissions.** Duo asks for `INTERNET` and `ACCESS_NETWORK_STATE`, both
ordinary, and neither needs a declaration form. It asks for no storage
permission, because the photo picker hands over one file at a time.

## 8. Closed testing first

Play requires new personal accounts to run a closed test with a number of
testers before going to production. Start one, put the two phones on it, and use
the app properly for a few days. Every mode, both axes, both orientations, a
long video, a short one, a page that works and a page that does not.

The one thing I could not test from here: **the web mode's zoom.** See the
README. Test that specifically.

## 9. Release

Promote the closed test to production once it behaves. Google reviews the first
submission, usually within a few days.

---

## What is deliberately not automated

The workflow builds and signs the bundle but does not upload it. Uploading needs
a Play service account with a JSON key, and an automated upload to a store
listing is not something to switch on before a human has watched it work once.
Add it later if you want it.
