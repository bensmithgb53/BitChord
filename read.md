# BitChord — Simple Change Log

**Rule:** Add new dated notes at the bottom. Do not delete or rewrite older notes.

## 2026-10-08 — Radio no-repeat fix

**What changed:** With **Don’t repeat songs** on, AutoPlay now compares the main title without extra text in brackets. For example, “Savage Love (Laxed - Siren Beat)” and “Savage Love” count as the same song even when the artist credits differ. If the original and a remix appear together, the original is preferred.

**Files changed:**
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt` — title matching and original-first ordering.
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt` — sends the no-repeat setting to the filter.
- `app/src/main/java/com/music/bitchord/MainActivity.kt` — applies the setting when starting radio.
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` — applies it when starting a station and refilling AutoPlay.

**Checks:** Added tests for the Savage Love example and other title cases. Shared JVM tests passed, and the Dev APK built successfully. The APK was not play-tested on a phone.

## 2026-10-09 — Code details for the no-repeat fix

### Queue filter (`QueueBuilder.kt`)

**Before:** A suggestion was rejected only when it matched the same recording (including artist credit).

```kotlin
if (taken.any { isSameRecording(it, candidate) }) continue
```

**After:** When **Don’t repeat songs** is on, it also rejects a matching base title when extra version text differs.

```kotlin
if (taken.any {
    isSameRecording(it, candidate) ||
        (avoidAlternateVersions && isSameSongAcrossMixVersions(it, candidate))
}) continue
```

The title-family check removes bracketed details before comparing:

```kotlin
title.replace(VERSION_PARENTHETICAL, " ")
```

So both `Savage Love (Laxed - Siren Beat)` and `Savage Love` reduce to `savage love`. Different artist credits do not let that version through. If the titles are already identical and have no version marker, the artist check stays, so unrelated songs called `Home` are not automatically blocked.

### Setting passed into the filter (`Autoplay.kt`)

**Before:**

```kotlin
QueueBuilder.extend(existing, related, limit)
```

**After:**

```kotlin
QueueBuilder.extend(existing, related, limit, avoidAlternateVersions)
```

`MainActivity.kt` and `PlaybackService.kt` pass the app’s **Don’t repeat songs** setting for a new radio and later AutoPlay refills.

## 2026-10-16 — Adaptive AutoPlay diversity

**What changed:** AutoPlay now gathers related candidates from the current track plus up to three recently played taste seeds. The queue builder softly prefers artists that have not appeared recently and rotates away from artists already used in the current batch, while still allowing a familiar artist when the radio has no good alternatives. No artist, genre, language, or style is hard-coded or blocked.

**Files changed:**
- `app/src/main/java/com/music/bitchord/playback/Autoplay.kt` — gathers multiple radio batches concurrently and merges them safely.
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` — keeps a bounded, in-memory history of tracks actually reached by playback as taste seeds; unplayed suggestions are not used as preference evidence.
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt` — applies soft recent-artist cooldown and within-batch artist rotation before existing duplicate/remix filtering.
- `app/src/test/java/com/music/bitchord/QueueBuilderTest.kt` — covers cooldown, diversity, and fallback behavior.

**Checks:** Shared JVM compilation and tests passed. Android unit tests/ APK build were not available in the sandbox because the Android SDK is not installed.


# BitChord AutoPlay continuation handoff

This section is a self-contained handoff for another AI/developer who needs to continue the authorized BitChord Android source work, build a compatible Dev APK, or investigate the remaining AutoPlay behavior. Treat the repository source as the primary truth. Do not blindly repeat old experiments: inspect the current branch and commit history first.

## Ready-to-use prompt for another AI

Copy the following prompt into the other AI:

> You are continuing authorized development of the open-source BitChord repository. Work from the local BitChord checkout, not from an APK-only reverse-engineering workflow unless source inspection proves it is necessary. The target branch is `experiment/autoplay-style-mix`; do not work on `main`. The production `dev` branch is separate and must not be changed or merged until the user explicitly asks and the current behavior is confirmed.
>
> The user owns/controls this test app and has authorized changes to the repository and Dev APK. You may inspect, modify, compile, sign, install, and test this source. Do not expose credentials, cookies, YouTube tokens, account IDs, private signing keys, or personal listening data. Do not bypass authentication, DRM, subscriptions, server authorization, or licensing. Keep diagnostics privacy-safe.
>
> First inspect:
>
> ```bash
> cd /home/ubuntu/BitChord
> git status --short --branch
> git log --oneline --decorate -30
> git branch -a
> ```
>
> Preserve user changes. Do not reset, clean, force-push, or overwrite untracked APKs without permission. Read this `read.md` and inspect the current source before changing anything.
>
> The current relevant branch is `experiment/autoplay-style-mix`. The latest source commit at this handoff is `1ae74b2d` (`Keep AutoPlay rows visible during timeline settling`). The latest tested APK is `BitChord-autoplay-current-index-fix.apk`.
>
> Principal source files:
>
> - `app/src/main/java/com/music/bitchord/playback/Autoplay.kt` — source-bucket AutoPlay planner, personal lanes, discovery, exploration, source rotation, duplicate-family filtering, direct artist recall, and diagnostics calls.
> - `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt` — shared ranking, style diversity, artist cooldown, and duplicate handling.
> - `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` — refill triggers, all-time history seed construction, AutoPlay feedback, persistent no-repeat usage, Radio separation, Repeat behavior, and queue appends.
> - `app/src/main/java/com/music/bitchord/data/stats/AutoplayTasteStore.kt` — persistent AutoPlay seeds, recent played IDs, recent AutoPlay artists, and cooldown data.
> - `app/src/main/java/com/music/bitchord/playback/AutoplayDiagnostics.kt` — privacy-safe persistent diagnostics export.
> - `app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt` — MediaController-to-Compose queue snapshot, MediaItem/Song conversion, queue-tier metadata, and the latest UI race fixes.
> - `app/src/main/java/com/music/bitchord/MainActivity.kt` — search-result playback path and player callbacks.
> - `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt` — splits the live queue into user, context, and AutoPlay rows.
> - `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` — AutoPlay diagnostics Settings entry.
>
> Current reported problem: diagnostics showed AutoPlay songs had loaded, but the player sometimes showed only `AutoPlay — Similar music will keep playing` with no rows. The latest changes address a UI/session timing race, not recommendation generation:
>
> 1. Search playback starts only the selected search result instead of carrying all same-title search results such as unrelated `Sorry` tracks by other artists.
> 2. The player UI reconciles its queue when the MediaController item count differs from the Compose snapshot.
> 3. The UI refreshes while foregrounded if the controller queue count or current index differs from the snapshot.
> 4. Media3 can transiently report `currentMediaItemIndex == -1` during a timeline append even when the current media item exists. `PlayerConnection.kt` now recovers the visible index by matching the current media ID in the queue snapshot.
> 5. AutoPlay queue tiers are carried through MediaItem metadata as `AUTOPLAY`; the UI uses `QueueTier.AUTOPLAY` to render the AutoPlay section.
>
> Do not remove the current-index recovery or queue reconciliation unless you first reproduce the bug and prove the replacement is safer. If the empty AutoPlay heading returns, instrument the MediaController snapshot with privacy-safe counts and queue-tier names only. Never log video IDs, URLs, cookies, tokens, account IDs, or file paths.
>
> Before a new code change, compare against the known intermittent baseline commit `4aa31eec` and the latest commits. The old intermittent behavior was not evidence that the recommendation engine was empty: diagnostics showed successful 10-track batches while the UI list was missing.
>
> Build and verification:
>
> ```bash
> cd /home/ubuntu/BitChord
> chmod +x gradlew
> ./gradlew :shared:jvmTest
> ```
>
> On the ARM64 sandbox, Android x86 AAPT2 may need the compatibility loader at `/tmp/aapt2-x86-root`:
>
> ```bash
> QEMU_LD_PREFIX=/tmp/aapt2-x86-root ./gradlew :shared:jvmTest :app:compileDevDebugKotlin :app:compileDevReleaseKotlin --no-daemon
> QEMU_LD_PREFIX=/tmp/aapt2-x86-root ./gradlew :app:assembleDev --no-daemon
> ```
>
> Universal Dev debug APK:
>
> ```text
> app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk
> ```
>
> Copy and verify it:
>
> ```bash
> cp app/build/outputs/apk/dev/debug/app-dev-universal-debug.apk BitChord-autoplay-next-fix.apk
> APK=BitChord-autoplay-next-fix.apk
> AAPT2=$(find "$ANDROID_HOME/build-tools" /usr/lib/android-sdk/build-tools -type f -name aapt2 2>/dev/null | sort -V | tail -1)
> APKSIGNER=$(find "$ANDROID_HOME/build-tools" /usr/lib/android-sdk/build-tools -type f -name apksigner 2>/dev/null | sort -V | tail -1)
> "$AAPT2" dump badging "$APK" | head -5
> "$APKSIGNER" verify --verbose --print-certs "$APK"
> sha256sum "$APK"
> stat -c '%s bytes, %y' "$APK"
> ```
>
> Install only on an authorized test device:
>
> ```bash
> adb devices
> adb install -r "$APK"
> adb shell am force-stop com.dev.bitchord.autoplaytest
> adb shell monkey -p com.dev.bitchord.autoplaytest 1
> adb logcat -c
> adb logcat -v threadtime | grep -i -E 'BitChord|AndroidRuntime|FATAL EXCEPTION'
> ```
>
> Dev build identity:
>
> ```text
> Dev package: com.dev.bitchord.autoplaytest
> App label: BitChord AutoPlay Test
> Production package: com.music.bitchord
> versionCode: 26
> versionName: 1.8
> minSdk: 26
> targetSdk: 36
> compileSdk: 37
> ABIs: armeabi-v7a, arm64-v8a, x86_64, plus universal APK
> ```
>
> Signing requirement: the verified current Dev APKs use certificate SHA-256 fingerprint `198a7b07dc861953fe14110e165f03d1e746d811c1790d2c1f84cdadc1f865a9`. A fingerprint does not recreate a private signing key. To update an already-installed Dev app, use the same authorized debug keystore that produced the installed APK, normally the same `~/.android/debug.keystore` on the original build environment, or a securely transferred authorized copy. Never put the keystore, passwords, or private key into Git, this README, or chat. If the original keystore is unavailable, a newly generated key will not install as an update; report that limitation instead of claiming it is the same signer. Always verify the actual APK with `apksigner verify --print-certs`.
>
> The Gradle file reads optional release signing information from an ignored root-level `keystore.properties` file with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Do not create or request those secrets in chat. Dev debug builds use the Android debug signing path; the exact signer still must be verified.
>
> Environment recovery if AAPT2 or x86 tools fail:
>
> ```bash
> uname -m
> java -version
> echo "$ANDROID_HOME"
> ls -ld /usr/lib/android-sdk /tmp/aapt2-x86-root
> find /usr/lib/android-sdk/build-tools -maxdepth 2 -type f -name aapt2 -print
> ```
>
> The successful build environment used Java 21, Gradle 8.11.1, Android platform 37, build-tools 35.0.1, NDK 27.0.12077973, CMake 3.22.1, and Ninja. A fresh authorized build machine may need:
>
> ```bash
> sudo apt-get update
> sudo apt-get install -y openjdk-21-jdk git curl unzip cmake ninja-build adb qemu-user-static
> export ANDROID_HOME=${ANDROID_HOME:-/usr/lib/android-sdk}
> export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$ANDROID_HOME}
> sdkmanager --licenses || true
> sdkmanager 'platform-tools' 'platforms;android-37' 'build-tools;35.0.1' 'ndk;27.0.12077973' 'cmake;3.22.1'
> ```
>
> Use the repository Gradle wrapper, not a random system Gradle. Do not change compile SDK, NDK, application ID, flavor, or signing behavior just to work around an environment problem. Record any unavoidable environment change.
>
> AutoPlay architecture currently implemented:
>
> - Independent personal-history, current-seed Radio, Discovery, and exploration candidate buckets instead of one Radio chain.
> - Multiple dynamic personal-history lanes in larger refills.
> - Older all-time history as rotating long-term anchors, balanced by artist.
> - Direct artist search/recall for familiar artists where available.
> - Unfamiliar-artist exploration slots without hard-coded genres or names.
> - Source-pool rotation across refills, including small one-song refills.
> - Radio sessions isolated from general personal AutoPlay taste anchors.
> - Persistent recent AutoPlay track IDs and artist cooldowns across restarts.
> - Duplicate-family filtering for originals, acoustic versions, radio edits, remixes, and instrumentals.
> - Completed songs as positive evidence and late skips treated cautiously so long outros are not false negative feedback.
> - Local privacy-safe diagnostics with bucket sizes, source order, seeds, and selected titles/artists but no IDs, URLs, tokens, cookies, account IDs, or file paths.
>
> Do not hard-code this user's artists or genres. Justin Bieber, N-Dubz, 50 Cent, The Script, Rihanna, Ed Sheeran, Drum and Bass, rap, pop, and R&B are test examples only. New behavior must be learned from each user's local history and session.
>
> Repeat/AutoPlay controls:
>
> - Repeat off: grey repeat arrows; use this for AutoPlay testing.
> - Repeat one: icon shows `1`; the same track repeats and is not a valid diversity test.
> - Repeat all: BitChord temporarily stashes pending AutoPlay tracks so the existing queue can loop; rows may disappear until Repeat all is turned off and the stash is restored/refilled.
> - AutoPlay on: highlighted AutoPlay control; it should append recommendations as tracks finish.
> - Shuffle should normally be off during diagnostics.
>
> Search requirement: tapping one search result such as `Sorry — Justin Bieber` must start only that selected result. Other same-title results must not become context queue items. AutoPlay recommendations must be appended separately and retain `QueueTier.AUTOPLAY`.
>
> Testing protocol:
>
> 1. Install over the existing Dev APK if preserving local test history is desired.
> 2. Confirm Repeat off/grey, AutoPlay on, Shuffle off.
> 3. Start from a known song or deliberately selected search result.
> 4. Open the queue after AutoPlay loads and confirm rows are visible below the AutoPlay heading.
> 5. Let several songs finish naturally; skipping every song is not required.
> 6. Reopen the queue after refills and after backgrounding/foregrounding.
> 7. Search `Sorry` and confirm unrelated artists are not immediate context tracks.
> 8. Export privacy-safe diagnostics if anything fails.
>
> Diagnostics interpretation:
>
> - `selected: 10` but no UI rows means recommendation generation worked; inspect service/session/UI queue state or queue-tier conversion.
> - `selected: 0` means inspect refill guards, current seed, repeat mode, network response, or filtering.
> - All source-order entries being Radio/current-seed entries means inspect source rotation and personal-lane availability, not hard-code a genre.
> - Radio contaminating later AutoPlay means inspect `radioName` filtering and history seed construction.
> - Heading present but rows absent means inspect `PlayerConnection.kt`, `QueueTier`, MediaItem extras, `PlayerQueue.splitQueue`, and MediaController event timing before changing ranking.
>
> Patch discipline:
>
> - Make small reversible changes.
> - Run `git diff --check`.
> - Run shared tests and Kotlin compilation after meaningful changes.
> - Build the universal Dev APK only after compilation succeeds.
> - Verify package, version, certificate, hash, and file size.
> - Do not claim a device fix without installing and testing on an authorized device.
> - Keep a patch log with file, behavior before/after, reason, command, and verification result.
> - Commit source changes only on `experiment/autoplay-style-mix` unless the user explicitly chooses another branch.
> - Never push APK binaries or signing secrets by accident.
> - If a result is unproven, state what still requires device testing.

## Project and branch status at handoff

- Working branch: `experiment/autoplay-style-mix`.
- Remote branch: `origin/experiment/autoplay-style-mix`.
- Related branches: `dev`, `fix/radio-no-repeat-versions`, `main`.
- Latest source commit: `1ae74b2d Keep AutoPlay rows visible during timeline settling`.
- Previous queue reconciliation: `1b59a61d Reconcile AutoPlay queue visibility in foreground`.
- Search playback fix: `a786d234 Fix search playback and AutoPlay queue visibility`.
- Familiar artist recall: `4aa31eec Add familiar artist recall lane to AutoPlay`.

The worktree contains multiple intentionally untracked historical test APKs. Do not run `git clean -fd` because it would delete them.

## AutoPlay change history

Useful commits, newest first:

```text
1ae74b2d Keep AutoPlay rows visible during timeline settling
1b59a61d Reconcile AutoPlay queue visibility in foreground
a786d234 Fix search playback and AutoPlay queue visibility
4aa31eec Add familiar artist recall lane to AutoPlay
fcb2c796 Ignore late AutoPlay skips as negative feedback
338483a3 Restore AutoPlay diagnostics Settings row
b73b0b21 Rotate named source buckets on AutoPlay refills
c71c4339 Add independent source buckets to AutoPlay
81c2d2da Keep personal history in AutoPlay refill rotation
1c26855a Build AutoPlay seeds from balanced all-time history
dbe0c01e Rotate recommendation pools across AutoPlay refills
29a50af2 Use discovery pools for small AutoPlay refills
cd1a7e7a Persist AutoPlay artist cooldown across restarts
0d5612d2 Prioritize fresh artists over recent refill chains
0bc76a5c Expand exploration and block alternate song versions
388bdf4b Add explicit new-artist exploration slots
41d2796c Enforce diverse personal lanes in AutoPlay queues
702280a Reserve two personal taste lanes in AutoPlay
3bd4aeb8 Use direct artist sources for Personal Mix
bf242866 Persist recent AutoPlay no-repeat history
```

The source-bucket design was informed by comparison with the original BitChord implementation and public open-source music-app patterns, including Spotui, LastWave-Native, and SimpMusic. No user-specific tastes or private code were copied into BitChord.

## Privacy-safe diagnostics

The exporter intentionally excludes YouTube video IDs, URLs, tokens, cookies, account IDs, and file paths. It reports aggregate counts, seed/title/artist labels, bucket sizes, source order, and selected titles/artists. Song titles and artist names are still listening-history information, so review an export before sharing it publicly.

## Build configuration reference

From `app/build.gradle.kts`:

```text
namespace: com.music.bitchord
production applicationId: com.music.bitchord
dev applicationId: com.dev.bitchord.autoplaytest
compileSdk: 37
targetSdk: 36
minSdk: 26
versionCode: 26
versionName: 1.8
NDK: 27.0.12077973
CMake: 3.22.1
ABIs: armeabi-v7a, arm64-v8a, x86_64
universal APK: enabled
```

`local.properties` should contain:

```properties
sdk.dir=/usr/lib/android-sdk
```

Optional local-only values include `LASTFM_API_KEY`, `LASTFM_SECRET`, and `LISTEN_TOGETHER_SERVER`. Do not commit them.

## Last verified APK

```text
/home/ubuntu/BitChord/BitChord-autoplay-current-index-fix.apk
```

Recorded verification:

```text
Package: com.dev.bitchord.autoplaytest
Version code: 26
Version name: 1.8
APK signature scheme v2: verified
Signer SHA-256: 198a7b07dc861953fe14110e165f03d1e746d811c1790d2c1f84cdadc1f865a9
APK SHA-256: fe6698f58bd66cefe1f44cae62c75a8242804ebab1ddbe7fa3174b783122a22
APK size: 188263797 bytes
```

The APK built successfully, but final intermittent queue behavior still requires real-device testing. A successful Gradle build is not proof that the device UI race is fixed.

## Authorized APK workflow note

If APK analysis is ever needed, use it only for an owned/open-source/explicitly authorized app. Prefer source-level fixes. Preserve an untouched input APK, record baseline metadata and SHA-256, use the least invasive change, verify DEX/resources/manifest/signature/install/launch, and never use APK work to bypass licensing, authentication, paid features, DRM, or server authorization. A signer fingerprint is not a private key and cannot recreate one.
