# Packaging a `.dmg`

```bash
./gradlew :desktop:packageDmg
# → desktop/build/compose/binaries/main/dmg/Loupe-1.0.0.dmg
```

That works today, on any Mac, with nothing installed beyond the repo. What follows is why each
piece of the configuration is there, and the one step that still needs an Apple account.

---

## The JDK inside the app

jpackage does not link against a JVM, it **copies one into the bundle** — so the JDK that builds
the app becomes part of the app, and its provenance becomes the app's provenance.

The Compose plugin refuses to package under Homebrew's OpenJDK outright
([compose-multiplatform#3107](https://github.com/JetBrains/compose-multiplatform/issues/3107)), and
it is right to: a runtime that is only ad-hoc signed can never be notarised once embedded.

Rather than making everyone install a second JDK, the build **provisions one**. The
`foojay-resolver-convention` plugin in `settings.gradle.kts` lets Gradle fetch an Adoptium 17 into
`~/.gradle/jdks`, and `desktop/build.gradle.kts` points `javaHome` at it — but **only when a
packaging task was actually asked for**, so an ordinary build never pays for the download.

## Apple forbids a leading zero

The project is at `0.1.0`. jpackage rejects it:

```
The first number in an app-version cannot be zero or negative.
```

So `macOS { packageVersion = "1.0.0" }`. That number belongs to Apple; the one a human reads is in
the git tag, the `.dmg`'s file name and the release notes. It is worth knowing this is a lie the
platform insists on, rather than rediscovering it at release time.

## One build per architecture

`compose.desktop.currentOs` resolves to the **build machine's** architecture. Skiko ships one native
library per arch and there is no universal binary, so an arm64 `.dmg` will not launch on an Intel
Mac. The release workflow runs a matrix — `macos-14` for arm64, `macos-13` for x64 — and names the
artifacts accordingly.

## Where the 63 MB goes

| | |
|---|---:|
| `.dmg` | **63 MB** |
| App bundle | 120 MB |
| ├ embedded runtime | 68 MB |
| └ app payload | 51 MB, of which ~29 MB is Skia |

The runtime is already minimal — jlink kept seven modules: `java.base`, `java.datatransfer`,
`java.xml`, `java.prefs`, `java.desktop`, `java.logging`, `jdk.crypto.ec`. `java.desktop` is the
large one and it is not optional; Compose Desktop is an AWT window.

**There is nothing meaningful left to trim.** Roughly 30 MB is Skia's native library, ~35 MB is the
AWT runtime, and the rest is Compose and Kotlin. Declaring fewer modules would only break it. This
is the floor for the stack, and it was worth measuring rather than assuming.

---

## Signing and notarisation — the part that needs your Apple account

Without it, macOS refuses the app on first launch. The user's way round is right-click → **Open**
→ **Open**, once, or `xattr -d com.apple.quarantine /Applications/Loupe.app`. The release notes say
so; that is honest but it is friction, and most people will not get past it.

**None of the following has been run** — it needs a certificate that only you can obtain, so treat
it as a checklist rather than as tested configuration.

### 1. Get the certificate

An Apple Developer account (99 €/year), then a **Developer ID Application** certificate from
[developer.apple.com/account/resources/certificates](https://developer.apple.com/account/resources/certificates).
Install it in your login keychain. Check it landed:

```bash
security find-identity -v -p codesigning
# → "Developer ID Application: Your Name (TEAMID)"
```

Then an **app-specific password** from [appleid.apple.com](https://appleid.apple.com) → Sign-In and
Security → App-Specific Passwords. Not your Apple ID password.

### 2. Turn it on in the build

In `desktop/build.gradle.kts`, inside `nativeDistributions { macOS { … } }`:

```kotlin
signing {
    sign.set(true)
    identity.set(providers.gradleProperty("loupe.signing.identity"))
}
notarization {
    appleID.set(providers.gradleProperty("loupe.notarization.appleId"))
    password.set(providers.gradleProperty("loupe.notarization.password"))
    teamID.set(providers.gradleProperty("loupe.notarization.teamId"))
}
```

Put the values in `~/.gradle/gradle.properties`, **never** in the repo:

```properties
loupe.signing.identity=Your Name (TEAMID)
loupe.notarization.appleId=you@example.com
loupe.notarization.password=abcd-efgh-ijkl-mnop
loupe.notarization.teamId=TEAMID
```

Then `./gradlew :desktop:notarizeDmg`. Apple's service takes a few minutes and answers with a log
URL when it refuses; the usual first refusal is an unsigned nested binary — for us that would be
`libskiko-macos-*.dylib`, which the plugin should sign along with everything else.

### 3. In CI

Add `MACOS_CERTIFICATE` (base64 of a `.p12`), `MACOS_CERTIFICATE_PWD`, `NOTARIZATION_APPLE_ID`,
`NOTARIZATION_PASSWORD` and `NOTARIZATION_TEAM_ID` as repository secrets, import the certificate
into a temporary keychain before the packaging step, and swap `packageDmg` for `notarizeDmg`.

The keychain dance is fiddly and I have not been able to test any of it, so
`.github/workflows/release.yml` deliberately builds **unsigned** rather than shipping a plausible
guess that fails on the day you actually cut a release.

### The alternative worth considering

[Conveyor](https://conveyor.hydraulic.dev) is free for open source and does signing, notarisation,
`.dmg`/`.msi`/`.deb`, and an update mechanism from one config file. If the keychain scripting turns
into an afternoon, it is the shorter road.

---

## Cutting a release

```bash
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` runs the tests, packages both architectures, attaches the two
`.dmg`s and their `.sha256` files to the GitHub release, and writes the Gatekeeper instructions into
the release notes. `workflow_dispatch` does the same on demand for a tag that already exists.
