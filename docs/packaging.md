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

## The `.dmg` layout comes from the Finder, not from jpackage

This one only shows up in CI, and it shows up as "the app cannot be installed".

jpackage does not lay the disk image out itself. It **drives the Finder**, through an AppleScript
(`DMGsetup.scpt`): the `/Applications` drop target, the icon positions and the background image all
come from that one step. The drop target it creates is a **Finder alias** — a 596-byte file, which
the POSIX layer spells `:Applications` — not a symlink.

A machine with no GUI session has no Finder to drive. jpackage logs a warning, carries on, and ships
a `.dmg` containing nothing but the `.app`: no background, no icon layout, and **no way to install
the app**. That is what a GitLab runner installed as a launchd *daemon* produces — the same missing
session that leaves the login keychain locked for signing.

`tools/package-dmg.sh` checks for the target and repairs it with a plain `ln -s /Applications`
(converting to a read-write image, adding the entry, recompressing). It only fires when the target
is missing, so a build on a real desktop keeps jpackage's layout untouched. A notarised image is
refused rather than repaired — editing it would break the staple.

**The repair is a safety net, not the fix.** It gets you an installable `.dmg`, but a bare one. The
fix is to give the runner a session, which also gets you signing:

```bash
gitlab-runner install    # as the runner user, not --user root
```

and log that user in once.

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

## Signing and notarisation

Without it, macOS refuses the app on first launch. The way round is right-click → **Open** →
**Open**, once, or `xattr -d com.apple.quarantine /Applications/Loupe.app`. That is honest, but it
is friction, and most people will not get past it.

**A self-hosted Mac runner makes this easy**, and that is worth saying plainly: the hard part of
notarising in CI is normally getting a certificate into a hosted runner — base64 a `.p12` into a
secret, import it into a temporary keychain, unlock it, tear it down. On your own Mac none of that
exists. The certificate lives in the login keychain once, the credentials live in that machine's
`~/.gradle/gradle.properties`, and **no secret ever touches the repository or GitLab**.

The build is already wired for it: `signing.sign` is on only when `loupe.signing.identity` is
present, so every other machine keeps producing an unsigned `.dmg` exactly as before, and
`tools/package-dmg.sh` runs `notarizeDmg` instead of `packageDmg` when it sees that property.

**None of the following has been run here** — it needs a certificate only you can obtain — so treat
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

### 2. Put the credentials on the machine

Nothing to change in the build — it already reads these. On the Mac mini runner, as the user the
runner runs as, in `~/.gradle/gradle.properties`. **Never** in the repo:

```properties
loupe.signing.identity=Your Name (TEAMID)
loupe.notarization.appleId=you@example.com
loupe.notarization.password=abcd-efgh-ijkl-mnop
loupe.notarization.teamId=TEAMID
```

Then `./tools/package-dmg.sh 0.1.0` on that machine notarises instead of just packaging. Apple's
service takes a few minutes and answers with a log URL when it refuses; the usual first refusal is
an unsigned nested binary — for us that would be `libskiko-macos-*.dylib`, which the plugin signs
along with everything else.

### 3. The one thing that bites on a runner

If the GitLab runner is installed as a **launchd daemon** rather than an agent, it has no login
session, and the login keychain stays locked — signing fails with a keychain error that does not say
so. Two fixes, in order of preference:

- Install the runner as a **launchd agent** in the runner user's session (`gitlab-runner install`
  without `--user root`), and log that user in once. The keychain then unlocks with the session —
  and the Finder becomes available, which is what lays the `.dmg` out.
- Or unlock it in the job, which means a keychain password in a masked CI variable:
  `security unlock-keychain -p "$KEYCHAIN_PWD" ~/Library/Keychains/login.keychain-db`.

The first keeps every secret off GitLab, which was the point.

### GitHub Actions stays unsigned

`.github/workflows/release.yml` deliberately builds unsigned. The hosted-runner keychain dance is
exactly what your own Macs let you skip, and shipping an untested version of it would only fail on
the day you cut a release.

### The alternative worth considering

[Conveyor](https://conveyor.hydraulic.dev) is free for open source and does signing, notarisation,
`.dmg`/`.msi`/`.deb`, and an update mechanism from one config file. If the keychain scripting turns
into an afternoon, it is the shorter road.

---

## Cutting a release

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Both CI systems are wired for it, and both call the **same** `tools/package-dmg.sh` — two CI files
that each spell out the packaging drift, and the one nobody watches is the one that breaks on
release day.

**Neither captures the script's stdout, and that is deliberate.** Doing it once fed Gradle's entire
log into `$GITHUB_OUTPUT`, which rejects a multi-line value — and only on a tag, so the first sight
of it was a failed release. The script empties `build/release/` before writing, so the folder is a
sufficient interface and nothing downstream can be poisoned by what some tool decides to print. The
script still prints only the path on stdout, and its header says so; that is now a convenience for
humans rather than something a release depends on.

### GitHub Actions

`.github/workflows/release.yml` runs the tests, packages both architectures on a matrix of
`macos-14` and `macos-13`, attaches the two `.dmg`s and their `.sha256` files to the release, and
writes the Gatekeeper instructions into the notes. `workflow_dispatch` does the same on demand for a
tag that already exists.

### GitLab CI, self-hosted

`.gitlab-ci.yml` is written for Docker runners plus a Mac mini shell runner. **Set the `docker` and
`macos` tags to whatever yours advertise** — they are placeholders.

**Build and test go on Docker, packaging on the Mac.** The build job runs on every push and merge
request, and the Macs are the scarce resource: they should be free for the thing that genuinely
needs them. Nothing in the test suite opens a window, so Linux is enough — proven by the GitHub
`ubuntu-latest` run getting through the whole suite.

A shell executor is not a container, and three things follow:

- **No `GRADLE_USER_HOME` override on the Mac job.** The runner's own `~/.gradle` persists between
  builds, which beats GitLab's cache — that one zips and unzips. It is also where the signing
  credentials live.
- **The working directory survives**, so `tools/package-dmg.sh` empties `build/release` before
  writing. Otherwise the previous tag's `.dmg` is still there when the release job globs the folder.
  This one is a real bug on a shell runner and would not show up on Docker at all.
- **The Gradle daemon survives**, and that is a feature. No `--no-daemon`.

The Mac needs only *a* JDK 17+ on `PATH` for Gradle to start. Which one does not matter: the
toolchain and the foojay resolver provision an Adoptium 17 for compiling and for jpackage, so even
a Homebrew JDK on the runner yields a correct bundle.

Two GitLab specifics worth knowing:

- **Test results land in the merge request** via `artifacts:reports:junit`, rather than in a log
  nobody opens. The one thing GitLab does better here out of the box.
- **A release links to assets, it does not host them**, so the `.dmg`s go to the project's generic
  package registry first and the release entry points at them.

### The images, and why they are what they are

**`eclipse-temurin:17-jdk-jammy` for the build.** Not the floating `17-jdk`: that tag follows the
base distribution and will step to a new Ubuntu under you, taking glibc with it — which is what
Skiko's Linux native links against. The JDK patch level floating is fine and wanted; the distro
moving underneath is not. If your runners pull from Docker Hub anonymously, **mirror this into your
own container registry**: the rate limit is per IP, and every runner behind the same NAT shares it.

**`release-cli` for the release, and nothing else runs there.** That image is Alpine with one binary
in it — **no `curl`**, no bash. So the upload to the package registry happens in `package:dmg`, on
the Mac, where curl is part of the system. Anything needing a tool belongs in that job, not this one.
Pin the tag to a version your instance supports rather than tracking `latest`, and mirror it too if
the runners cannot reach `registry.gitlab.com`.

**The Mac job has no `image:` at all** — a shell executor ignores it. That is the tell that it runs
on the machine itself.

### Two things that bite in the `release:` block

It expands `$VAR`, but it is **not a shell**: `${CI_COMMIT_TAG#v}` there is taken literally, so the
asset link would point at `…/loupe/v0.1.0/` while the upload went to `…/loupe/0.1.0/`. A dead link,
discovered on release day. So `package:dmg` computes `PKG_VERSION` and `DMG_NAME` and hands them
over as a **dotenv report**; the release block uses plain `$PKG_VERSION` and `$DMG_NAME`.

For those variables to cross, `needs` must carry `artifacts: true` — it is what transports the
dotenv report, not just the files.

### Intel

Both CI configs build for the machine they run on. If you need an x64 `.dmg`, add a second Mac
runner with a distinguishing tag and a second `package:dmg` job — or run the script by hand on an
Intel machine and attach the result. Saying which architecture a build is for in the release notes
matters either way; an arm64 build does not launch on Intel.

> **Tested:** the packaging script, on this machine, producing a correctly named 63 MB `.dmg` with
> its checksum, and that all three CI files parse. **Reviewed, not executed:** everything in
> `.gitlab-ci.yml` past the build job, and the whole signing path — neither has a GitLab project or
> a certificate to run against.
