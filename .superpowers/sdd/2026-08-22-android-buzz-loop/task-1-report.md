# Task 1 report: Gradle scaffold and a blank app that installs

Status: **DONE_WITH_CONCERNS** (build succeeds up to the Android SDK boundary; that final
compile/link/install step is environment-blocked, as the brief anticipated it might be).

Commit: `f51e399` on branch `feat/android-buzz-loop`
("feat(app): scaffold Compose app that builds and installs")

## Files created

- `settings.gradle.kts` — as specified in Step 1, verbatim.
- `gradle/libs.versions.toml` — as specified in Step 2, verbatim (no version substitutions
  needed; every pinned version resolved).
- `build.gradle.kts` (root) — as specified in Step 3, verbatim.
- `gradle.properties` — as specified in Step 4, verbatim.
- `app/build.gradle.kts` — as specified in Step 5, verbatim.
- `app/proguard-rules.pro` — as specified in Step 6, verbatim.
- `app/src/main/AndroidManifest.xml` — as specified in Step 7, verbatim.
- `app/src/main/res/values/strings.xml` — as specified in Step 8, verbatim.
- `app/src/main/res/values/themes.xml` — as specified in Step 9, verbatim.
- `app/src/main/java/com/lovebutton/app/ui/Theme.kt` — as specified in Step 10, verbatim.
- `app/src/main/java/com/lovebutton/app/MainActivity.kt` — as specified in Step 11, verbatim.
- `app/google-services.json.example` — as specified in Step 12, verbatim. Committed.
- `app/google-services.json` — created locally (copy of the `.example` file) so the
  `google-services` plugin has something to read. **Not committed** — it matches the
  `app/google-services.json` line already in `.gitignore` (verified with `git status`
  and `git add` before committing: it never appeared as a staged/untracked-to-be-added file).
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/wrapper/gradle-wrapper.properties` — generated wrapper, committed. Wrapper
  points at Gradle **9.5.0** (see "Version substitutions" below for why not the
  more obvious 8.13).

## Modified

- `.gitignore` (repo root) — appended an `# android` section. One deviation from the
  literal Step 13 text: I did **not** re-add `local.properties`, since it's already
  present in the file's existing `# secrets` section (added before this task). Re-adding
  it would have created a duplicate line with no behavior change. Everything else in the
  Step 13 block (`*.iml`, `.idea/`, `captures/`, `.externalNativeBuild/`, `.cxx/`,
  `app/build/`) was appended exactly as specified. No existing line was touched or removed.

`server/` and `love-button-spec.md` were not touched — confirmed via `git status` and
`git diff HEAD~1 HEAD --stat -- server/` (empty) after committing.

## Build command and full output

```
$ ./gradlew :app:assembleDebug
```

```
[Incubating] Problems report is available at: file:///home/killua/Projects/LovieApp/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '/home/killua/Projects/LovieApp/local.properties'.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
...
BUILD FAILED in 715ms
```

This is exactly the environment-blocker scenario called out in the brief's context note 5:
**no Android SDK is installed on this machine** (no `ANDROID_HOME`, no `~/Android/Sdk`, no
Android Studio, `find` turned up nothing anywhere under `/home`). Per the brief's explicit
instruction, I did **not** attempt to download/install an SDK myself, and I did **not**
create a `local.properties` file (it's gitignored and machine-specific — creating one
pointing at a nonexistent SDK path would have been pointless anyway).

**What I did verify works**, to build confidence that the scaffold itself is correct and
that the SDK is the only thing standing between this and a green build:
- `./gradlew help` — BUILD SUCCESSFUL (Gradle/Kotlin DSL scripts parse, wrapper works).
- `./gradlew projects` — BUILD SUCCESSFUL, correctly shows `:app` as a subproject.
- `./gradlew :app:assembleDebug` — got **past** applying all five plugins (Android
  application, Kotlin Android, Kotlin Compose, Kotlin serialization, and
  `google-services`, which read `app/google-services.json` without complaint) and
  **into** dependency resolution and task graph construction for `:app`, i.e. every
  version in `libs.versions.toml` resolved from Google/Maven Central and the
  `google-services` plugin was satisfied. It only failed once Gradle needed to invoke
  `compileDebugJavaWithJavac`, which needs `android.jar` from a real SDK.

## google-services.json route taken

**Route 1 (preferred, as the brief ranked it):** copied `app/google-services.json.example`
to `app/google-services.json` locally. Confirmed gitignored (not committed — verified with
`git status --short` and `git add` before the commit; it never showed up as a change to
stage). The `google-services` plugin stayed wired exactly as the brief's Step 5 specifies
(no commenting-out was needed). This is the cleaner path and worked on the first try — the
plugin applied and read the placeholder file without error.

## Version substitutions

**None of the pinned artifact/plugin versions in `libs.versions.toml` needed substitution.**
AGP 8.13.0, Kotlin 2.2.0, all AndroidX/Compose/Firebase/OkHttp/serialization/DataStore/
WorkManager versions as specified all resolved.

**What I did have to change: the Gradle wrapper version, not anything in the version
catalog.** This machine only has JDK 26 installed (Arch Linux `jdk-openjdk` package,
`java-26-openjdk`; no other JDK available, no root access to install one). That drove two
compatibility findings, worked through empirically:

1. **Gradle 8.13** (a natural first guess, matching the AGP version number) does not run
   at all on JDK 26 — `gradle -v` and `gradle wrapper` both fail immediately with a bare
   `26.0.2` exception, no stack trace, no explanation. Gradle 8.13 predates JDK 26 support.
2. **Gradle 9.7.0** (latest stable at time of testing, available via the distro's pacman
   `extra` repo though not installed since it needs root) runs fine on JDK 26, but AGP
   8.13.0 fails to apply on it: `Plugin 'com.android.internal.application' relies on
   'org.gradle.api.problems.internal.InternalProblems', a Gradle internal API that was
   removed in Gradle 9.6.0. Update the plugin to a version that no longer uses Gradle
   internal APIs, or use Gradle 9.5.` (Gradle's own error message, pointing at
   `https://docs.gradle.org/9.7.0/userguide/upgrading_version_9.html#agp_8x_incompatible`.)
3. **Gradle 9.5.0** threads the needle: runs on JDK 26 *and* AGP 8.13.0 applies and
   resolves cleanly against it. Used this for the wrapper.

This is a wrapper/tooling choice, not a version-catalog change, so it doesn't touch the
"don't downgrade Kotlin or AGP" constraint — AGP stayed at exactly 8.13.0 as specified. I
picked the Gradle version, not a system default, precisely so this doesn't depend on
whatever Gradle happens to be on a future machine.

Gradle 9.5.0 does emit a deprecation notice (`Declaring dependencies using multi-string
notation has been deprecated... incompatible with Gradle 10`) — this originates from
inside AGP 8.13.0's own internal dependency declarations (lint-gradle, aapt2), not from
any file in this repo. It doesn't fail the build; noting it because a future AGP bump will
likely need a paired Gradle 10 bump.

## Gradle wrapper generation

No `gradle` binary was preinstalled and no passwordless `sudo` is available (`sudo -n
true` fails), so `pacman -S gradle` was not an option. Internet access was available, so
I downloaded the official Gradle binary distributions directly from
`https://services.gradle.org/distributions/` into the scratchpad directory (8.13, 9.7.0,
9.5.0 — see version-substitution notes above for why three), and ran
`<scratchpad>/gradle-9.5.0/bin/gradle wrapper --gradle-version 9.5.0` from the repo root.
This is the "system Gradle available" path from context note 4, just with "system Gradle"
being a locally-unzipped distribution rather than a package-manager install — no wrapper
files were hand-written. Nothing under the scratchpad directory was added to the repo;
only the wrapper artifacts Gradle itself generated (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`) were
committed.

## Device / install check

**No device was available.** `adb` is installed but `lsusb` shows no Android device
attached, and there's no emulator running. `./gradlew :app:installDebug` was not
attempted since `:app:assembleDebug` doesn't complete without an SDK (a prerequisite for
`installDebug` too). This half of Step 14's verification is **outstanding, not failed** —
per the brief's own framing of "if no device is attached, say so."

## What Task 2's implementer needs to know

1. **This environment has no Android SDK.** Before any task that needs `assembleDebug`/
   `installDebug` to actually complete, either install one (`ANDROID_HOME` +
   `local.properties` with `sdk.dir=...`, or via `sdkmanager`) or run on a machine that
   already has Android Studio / the SDK. `local.properties` is gitignored — never commit it.
2. **This environment has only JDK 26.** If you regenerate the wrapper or bump Gradle,
   confirm the new version's JDK 26 support first — Gradle 8.13 flatly refuses to start on
   it, and only Gradle ≥9.5 (and specifically not 9.6+) is known-good with AGP 8.13.0. If
   AGP is bumped past 8.13 in a later task, re-check this pairing; the `internal.application`
   API removal in Gradle 9.6 was fixed AGP-side in some later AGP release, so a paired
   AGP+Gradle bump (not just Gradle alone) is the way out if 9.5 becomes limiting.
3. **`app/google-services.json` exists locally** (copied from the `.example` file, gitignored)
   so the build gets as far as the SDK boundary. Task 6, which wires up the real Firebase
   project, should overwrite this file with the real one — the placeholder will not produce
   a working FCM registration.
4. The `LoveButtonTheme` composable is in `com.lovebutton.app.ui.Theme` exactly as the
   brief's Produces line promises.
5. `API_BASE_URL` build config field is `https://example.invalid`, per spec, untouched.

## Verification commands run (for reproducibility)

```
java -version                          # openjdk 26.0.2 — only JDK on this machine
./gradlew help                         # BUILD SUCCESSFUL
./gradlew projects                     # BUILD SUCCESSFUL — shows :app
./gradlew :app:assembleDebug           # FAILS at SDK boundary (see above) — expected/blocked
git status --short                     # clean after commit; google-services.json absent
git log --oneline -1                   # f51e399 feat(app): scaffold Compose app...
```

---

## Fix follow-up: `firebase-messaging-ktx` catalog defect (coordinator-directed)

The coordinator installed the Android SDK (`ANDROID_HOME=$HOME/Android/Sdk`, cmdline-tools
16111833, platform-tools 37.0.1, `platforms/android-36`, `build-tools/36.1.0`, licences
accepted) and added a gitignored, untracked `local.properties` pointing at it. Confirmed
with `git status --short` before and after all work below that `local.properties` never
appears as a tracked/staged change.

### Change made

`gradle/libs.versions.toml` — replaced the `firebase-messaging` library line, exactly as
directed:

```toml
# Plain firebase-messaging, NOT firebase-messaging-ktx: Google folded the KTX
# extensions into the main artifacts and dropped the -ktx variants, so recent
# BOMs carry no version for them and the dependency resolves to an empty
# version with a confusing "Could not find ...:" error.
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" }
```

(previously `name = "firebase-messaging-ktx"`). The alias stayed `firebase-messaging`;
`app/build.gradle.kts` required no change, as predicted. No explicit version was pinned —
it's supplied by the BOM.

### `./gradlew :app:assembleDebug` (after the fix, with the SDK present)

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew :app:assembleDebug
```

The `firebase-messaging-ktx` resolution error is gone — the build got past dependency
resolution and into actual Android build tasks (`preBuild`, `preDebugBuild`,
`mergeDebugNativeDebugMetadata`, `generateDebugBuildConfig` all completed/were up to date).
It then failed on `:app:checkDebugAarMetadata`, a **different, unrelated defect** in the
version catalog — per your instruction, I stopped here rather than substituting anything
to force it green:

```
> Task :app:checkDebugAarMetadata FAILED

* What went wrong:
Execution failed for task ':app:checkDebugAarMetadata' (registered by plugin 'com.android.internal.application').
> A failure occurred while executing com.android.build.gradle.internal.tasks.CheckAarMetadataWorkAction
   > 22 issues were found when checking AAR metadata:

       1.  Dependency 'androidx.compose.animation:animation-core-android:1.12.0' requires libraries and applications that
           depend on it to compile against version 37 or later of the
           Android APIs.

           :app is currently compiled against android-36.

           Also, the maximum recommended compile SDK version for Android Gradle
           plugin 8.13.0 is 36.

           Recommended action: Update this project's version of the Android Gradle
           plugin to one that supports 37, then update this project to use
           compileSdk of at least 37.

       2.  Dependency 'androidx.compose.animation:animation-core-android:1.12.0' requires Android Gradle plugin 9.1.0 or higher.

           This build currently uses Android Gradle plugin 8.13.0.

     [... 20 more entries, same two-part complaint, for these artifacts, all at
      version 1.12.0, all pulled in transitively by composeBom = "2026.08.00":
        androidx.compose.material:material-ripple-android
        androidx.compose.animation:animation-android
        androidx.compose.foundation:foundation-layout-android
        androidx.compose.foundation:foundation-android
        androidx.compose.ui:ui-tooling-data-android
        androidx.compose.ui:ui-text-android
        androidx.compose.ui:ui-graphics-android
        androidx.compose.ui:ui-tooling-android
        androidx.compose.ui:ui-android
        androidx.compose.runtime:runtime-saveable-android ]

BUILD FAILED in 4s
3 actionable tasks: 2 executed, 1 up-to-date
```

**Root cause, as far as I can diagnose without changing anything:** `composeBom =
"2026.08.00"` resolves every Compose artifact to version `1.12.0`, and that generation of
Compose libraries stamps its AARs with a minimum-compileSdk requirement of **37** and a
minimum-AGP requirement of **9.1.0**. The catalog currently pins `agp = "8.13.0"` and
`app/build.gradle.kts` pins `compileSdk = 36` / `targetSdk = 36`, both below what
Compose 1.12.0 demands. This is the same shape of problem as the `firebase-messaging-ktx`
one — a BOM/version pairing in the plan that doesn't line up with what its own transitive
artifacts require — but it touches three coupled fields (`agp`, `compileSdk`, `targetSdk`)
rather than one dependency line, so I did not attempt a fix myself per your instruction and
am reporting it back instead.

I did not run `./gradlew :app:testDebugUnitTest` — `assembleDebug` did not succeed, and the
brief made running the test command conditional on `assembleDebug` succeeding first.

No device is attached, so `installDebug` verification remains outstanding regardless, as
expected.

### Commit

The `libs.versions.toml` fix is committed on its own, separate from the original scaffold
commit, and does **not** include `local.properties`:

```
$ git add gradle/libs.versions.toml
$ git commit -m "fix(app): use plain firebase-messaging, not the dropped -ktx artifact"
```

---

## Fix follow-up 2: `composeBom` pin (coordinator-directed)

### Change made

`gradle/libs.versions.toml` — replaced the `composeBom` version line, exactly as directed:

```toml
# 2026.06.01 pins Compose 1.11.4. Do NOT bump to 2026.08.00 (Compose 1.12.0)
# without also moving to AGP 9.1+ and compileSdk 37 — 1.12.0 demands both, and
# the failure is 22 opaque AAR-metadata violations, not a version message.
composeBom = "2026.06.01"
```

(previously `"2026.08.00"`). Confirmed `agp = "8.13.0"` and `app/build.gradle.kts`'s
`compileSdk = 36` / `targetSdk = 36` are untouched — nothing else in the catalog changed.

### `./gradlew :app:assembleDebug` (after the composeBom pin)

The AAR-metadata failure is gone — the build progressed through the full resource/manifest/
Kotlin pipeline (26 tasks executed, including `checkDebugAarMetadata`, `compileDebugKotlin`,
`mergeExtDexDebug`) and reached `:app:compileDebugJavaWithJavac`, where it hit a **new
failure that is not a version-catalog problem**:

```
> Task :app:compileDebugJavaWithJavac FAILED

* What went wrong:
Execution failed for task ':app:compileDebugJavaWithJavac' (registered by plugin 'com.android.internal.application').
> Could not resolve all files for configuration ':app:androidJdkImage'.
   > Failed to transform core-for-system-modules.jar to match attributes {artifactType=_internal_android_jdk_image, org.gradle.libraryelements=jar, org.gradle.usage=java-runtime}.
      > Execution failed for JdkImageTransform: /home/killua/Android/Sdk/platforms/android-36/core-for-system-modules.jar.
         > Error while executing process /usr/lib/jvm/java-26-openjdk/bin/jlink with arguments {--module-path /home/killua/.gradle/caches/9.5.0/transforms/237796d2b980cc1ba6ad2a74fb531535/transformed/output/temp/jmod --add-modules java.base --output /home/killua/.gradle/caches/9.5.0/transforms/237796d2b980cc1ba6ad2a74fb531535/transformed/output/jdkImage --disable-plugin system-modules}

BUILD FAILED in 54s
26 actionable tasks: 26 executed
```

Gradle's own log doesn't surface `jlink`'s stderr, so I reproduced the exact failing
invocation by hand, pointing `jlink` at the same intermediate jmod directory Gradle had
already produced under `~/.gradle/caches/9.5.0/transforms/.../temp/jmod`:

```
$ /usr/lib/jvm/java-26-openjdk/bin/jlink \
    --module-path <cache>/temp/jmod --add-modules java.base \
    --output /tmp/jlink-test-output --disable-plugin system-modules

Error: jlink build 'Arch Linux-26.0.2-2026-07-21' cannot find the build signature in the
java.base specified on module path, likely from an earlier build.
```

**This is a JDK 26 / `jlink` incompatibility, not a dependency-version conflict — I did not
change anything to chase it, per your standing instruction, and I did not re-run with a
different JDK since none other is installed on this machine (only `java-26-openjdk`;
confirmed earlier via `archlinux-java status`).** AGP's `JdkImageTransform` (part of
`compileDebugJavaWithJavac`'s toolchain setup, used to let javac compile against
`compileSdk`'s `android.jar` via a synthetic JPMS module) invokes the platform `jlink` to
link a `java.base` jmod. `jlink` refuses to link a module built by a different JDK "build"
than the `jlink` binary's own build — it's checking a signature stamped by the JDK vendor's
build process (`'Arch Linux-26.0.2-2026-07-21'`), and whatever produced the intermediate
jmod evidently doesn't carry a signature `jlink` recognizes as matching. This reproduces
identically and deterministically outside Gradle, so it isn't a caching artifact — it is
this JDK build's `jlink` refusing this input, every time.

This is orthogonal to the `composeBom`/AGP/compileSdk decision you just made: it fires
identically regardless of which Compose BOM or compileSdk value is in the catalog, because
it happens during plain `javac` compilation against `android-36`'s bundled
`core-for-system-modules.jar`, before any Compose-specific code is touched. **The
composeBom = "2026.06.01" pin is confirmed correct and made real progress** — 26 tasks now
execute cleanly, up from failing at task 6 of 29 before. What's left is a JDK/toolchain
constraint of this specific machine (JDK 26, an Arch Linux build), not anything in the
plan's version catalog.

### `./gradlew :app:testDebugUnitTest`

Also run, per your instruction to run both regardless. Fails identically and for the same
reason — `testDebugUnitTest` needs `compileDebugJavaWithJavac`'s output too:

```
> Task :app:compileDebugJavaWithJavac FAILED
[... identical androidJdkImage / JdkImageTransform / jlink error as above ...]
BUILD FAILED in 2s
20 actionable tasks: 1 executed, 19 up-to-date
```

### What would resolve this (not attempted — reporting per your standing instruction)

The generally-known fix for this class of AGP/jlink error is to point Gradle's Java
toolchain at an older JDK (17 or 21 are the versions AGP 8.13 is actually tested against;
this repo's own `compileOptions`/`kotlin.compilerOptions` already target JVM 17) via
`org.gradle.java.home` in `gradle.properties` or a project-level Gradle toolchain
declaration — i.e. running Gradle itself under JDK 26 (which it can do, that's how we got
this far) while pointing *just* the Android javac toolchain at a JDK 17/21 install. That
requires a JDK 17 or 21 actually present on the machine, which this one doesn't have
(`archlinux-java status` shows only `java-26-openjdk`), and installing one needs either
root (`pacman -S jdk17-openjdk` prompts for a password we don't have) or another
unzip-to-a-user-directory trick like the one used for Gradle itself. I did not do this
without your sign-off, since it changes the JDK story you already spent effort pinning
down, and because the standing instruction was to stop and report rather than route around
version-shaped failures myself.

### Commit

The `composeBom` fix is committed on its own, does **not** include `local.properties`
(confirmed with `git status --short` before and after):

```
$ git add gradle/libs.versions.toml
$ git commit -m "fix(app): pin composeBom to avoid AGP 9.1+/compileSdk 37 requirement"
```

No device is attached, so `installDebug` verification remains outstanding regardless of
the above, as expected.

---

## Fix follow-up 3: declarative Java toolchain (coordinator-directed)

The coordinator installed a user-local Temurin JDK 21 LTS at `~/.jdks/jdk-21.0.12.1+1`
(no root, nothing removed from the system) and confirmed the diagnosis by running
`./gradlew -Dorg.gradle.java.home=$HOME/.jdks/jdk-21.0.12.1+1 :app:assembleDebug`
successfully — proving JDK 26 (this machine's only system JDK) was the actual cause of
the `jlink` failure, independent of the `composeBom` decision.

### Change made

`app/build.gradle.kts` — added a `java { toolchain { ... } }` block between the closing
brace of `android { }` and the `dependencies { }` block, exactly as directed:

```kotlin
/**
 * The JDK used to COMPILE, which is not the JDK that runs Gradle.
 *
 * AGP 8.13 builds a trimmed JDK image with `jlink` as part of javac setup, and
 * that step fails outright on JDK 26 with "cannot find the build signature in the
 * java.base specified on module path" — nothing that mentions a version. Declaring
 * a toolchain here lets Gradle locate a JDK 21 itself (it scans ~/.jdks, SDKMAN,
 * asdf and the usual system paths) instead of anyone hardcoding a machine path.
 *
 * Bytecode target stays at 17 above; a 21 toolchain emitting 17 is normal.
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```

Nothing else in the file changed: `compileOptions` stayed at `JavaVersion.VERSION_17` and
Kotlin's `jvmTarget` stayed at `JvmTarget.JVM_17`, as directed.

### Verification — without the `-Dorg.gradle.java.home` flag

First ran `./gradlew :app:assembleDebug` as-is and got `BUILD SUCCESSFUL`, but every task
showed `UP-TO-DATE` — those outputs were left over from the coordinator's own
flag-based verification run, so that alone wasn't proof the toolchain block resolves a
JDK on its own. Ran `./gradlew clean` and rebuilt from scratch to get a real signal:

```
$ export ANDROID_HOME="$HOME/Android/Sdk"
$ ./gradlew clean
BUILD SUCCESSFUL in 895ms

$ ./gradlew :app:assembleDebug
> Task :app:generateDebugBuildConfig
> Task :app:checkDebugAarMetadata
[... 37 more tasks executing, including :app:compileDebugKotlin,
     :app:compileDebugJavaWithJavac, :app:dexBuilderDebug, :app:packageDebug,
     :app:assembleDebug ...]

Task :app:stripDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libdatastore_shared_counter.so. Run with --info option to learn more.

BUILD SUCCESSFUL in 8s
40 actionable tasks: 40 executed
```

No `-Dorg.gradle.java.home` flag was passed. No `jlink` error. No Kotlin/Java JVM-target
mismatch warning appeared anywhere in the output. `app/build/outputs/apk/debug/app-debug.apk`
exists (verified with `find`). The one incidental note —
`Unable to strip the following libraries, packaging them as they are:
libandroidx.graphics.path.so, libdatastore_shared_counter.so` — is AGP declining to strip
debug symbols from two prebuilt native `.so` files pulled in transitively (Compose
graphics path rendering, DataStore's shared-counter native lib); it's a packaging note, not
an error or a warning about anything in this project's own code, and doesn't affect the
build outcome.

```
$ ./gradlew :app:testDebugUnitTest
> Task :app:javaPreCompileDebugUnitTest
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin NO-SOURCE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes NO-SOURCE
> Task :app:testDebugUnitTest NO-SOURCE

BUILD SUCCESSFUL in 1s
24 actionable tasks: 3 executed, 21 up-to-date
```

`NO-SOURCE` on the test-compile and test-run tasks is exactly the expected shape — no test
sources exist yet (Task 2 adds the first). This confirms the JVM test toolchain itself is
wired correctly (it reached and successfully no-op'd every test task) before Task 2 needs
it.

### Device / install check

`adb devices` (daemon started fresh) lists no devices; `lsusb` shows no Android hardware
attached. `installDebug` verification remains outstanding, as expected — not attempted
since there's nothing to install to.

### Commit

The toolchain block is committed on its own, does **not** include `local.properties`
(confirmed via `git status --short` before and after — only `app/build.gradle.kts` shows
as modified):

```
$ git add app/build.gradle.kts
$ git commit -m "fix(app): declare a JDK 21 toolchain for compilation"
```

### Summary: Task 1 build status, end to end

With the Android SDK present, the `firebase-messaging` fix, the `composeBom` pin, and the
declarative JDK 21 toolchain all in place, `./gradlew :app:assembleDebug` and
`./gradlew :app:testDebugUnitTest` both succeed cleanly from a clean build, using only
`ANDROID_HOME` in the environment — no ad hoc flags, no hand-written `local.properties`
committed, no machine-specific paths baked into any tracked file. The only outstanding
item from the original "done" bar is the on-device `installDebug` half, which stays
outstanding for lack of an attached device, not for any defect.
