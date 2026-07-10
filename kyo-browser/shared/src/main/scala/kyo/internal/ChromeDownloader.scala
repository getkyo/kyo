package kyo.internal

import kyo.*

/** Downloads and caches `chrome-headless-shell` (Google's lightweight headless build of Chrome) on demand.
  *
  * The first call downloads the requested version to a user-local cache; subsequent calls reuse the cached executable. Downloads come from
  * Google's official Chrome-for-Testing archive (`https://storage.googleapis.com/chrome-for-testing-public/`).
  *
  * `chrome-headless-shell` is the same headless code path full Chrome runs under `--headless=new`, packaged as a standalone binary without
  * the GUI compositor, GPU stack, extension loader, or audio. ~120 MB compressed (vs ~190 MB for full Chrome), faster startup, smaller
  * memory footprint, fully CDP-compatible. This is Puppeteer's default since v22.
  *
  * The cache directory defaults to `{Path.basePaths.cache}/kyo-browser/`. The `KYO_BROWSER_CACHE` environment variable overrides it.
  *
  * The platform code (`mac-arm64`, `mac-x64`, `linux64`, `win64`, `win32`) is derived from `kyo.System.operatingSystem` and
  * `kyo.System.architecture`. Google publishes no `linux-arm64` artifact, so `resolvePlatform` aborts on that tuple with a message pointing
  * users at `LaunchConfig.chromium(...)` for system-installed Chromium.
  *
  * Used internally by [[kyo.Browser.chromeForTestingLaunchConfig]] and the zero-arg [[kyo.Browser.run]] overload.
  */
private[kyo] object ChromeDownloader:

    /** Looks up the latest known-good Chrome-for-Testing Stable version from the metadata endpoint configured on
      * `cfg.metadataUrl`. On HTTP failure (404, timeout, offline) logs a warning and returns `cfg.fallbackVersion`
      * so the caller can still proceed in offline environments.
      *
      * No process-level cache: the metadata fetch is once per process per launch path, not a hot loop, so
      * re-fetching on each call is acceptable.
      */
    def latestVersion(cfg: Browser.LaunchConfig.ChromeDownloaderConfig)(using
        Frame
    ): String < (Async & Abort[BrowserSetupException]) =
        Abort.run[HttpException](HttpClient.getJson[ChromeForTestingMetadata](cfg.metadataUrl)).map {
            case Result.Success(meta) => meta.channels.Stable.version
            case Result.Failure(err) =>
                Log.warn(s"ChromeDownloader.latestVersion: metadata lookup failed ($err); falling back to ${cfg.fallbackVersion}")
                    .andThen(cfg.fallbackVersion)
            case Result.Panic(ex) =>
                Log.warn(
                    s"ChromeDownloader.latestVersion: metadata lookup panicked (${ex.getMessage}); falling back to ${cfg.fallbackVersion}"
                ).andThen(cfg.fallbackVersion)
        }
    end latestVersion

    /** Ensures a Chrome-for-Testing binary is available and returns the absolute path to its executable.
      *
      * `version = Absent` resolves the latest known-good Stable version dynamically via [[latestVersion]] using the
      * supplied `cfg.metadataUrl`; `Present(v)` pins to a caller-supplied version. `build` selects which artifact
      * to download (`chrome-headless-shell` or full `chrome`); each `build` caches independently so the two can
      * coexist. Safe to call repeatedly; the cached binary is reused once downloaded.
      */
    def ensure(
        version: Maybe[String],
        cfg: Browser.LaunchConfig.ChromeDownloaderConfig,
        build: Browser.ChromeForTestingBuild
    )(using Frame): String < (Async & Abort[BrowserSetupException]) =
        ensureWith(version, cfg, build, downloadAndExtract(_, _, _, _, cfg.downloadTimeout))

    /** Test seam: same as [[ensure]] but allows the download function to be substituted. */
    def ensureWith(
        version: Maybe[String],
        cfg: Browser.LaunchConfig.ChromeDownloaderConfig,
        build: Browser.ChromeForTestingBuild,
        download: (Browser.ChromeForTestingBuild, String, String, Path) => Unit < (Async & Abort[BrowserSetupException])
    )(using Frame): String < (Async & Abort[BrowserSetupException]) =
        // `platformCode` is evaluated first so unsupported tuples (e.g. linux-arm64) abort BEFORE any network I/O.
        // Otherwise `latestVersion` issues an HTTPS request to the Chrome-for-Testing metadata endpoint, and slow CI
        // runners can take longer than a test's own timeout (~60s) to complete it, swallowing the
        // unsupported-platform signal that downstream test bases translate into a ScalaTest `cancel(...)`.
        val resolveVersion: String < (Async & Abort[BrowserSetupException]) = version match
            case Present(v) => v
            case Absent     => latestVersion(cfg)
        for
            platform <- platformCode
            v        <- resolveVersion
            root     <- cacheRoot
            versionDir = root / s"${artifactName(build)}-$v-$platform"
            exec       = executablePath(versionDir, platform, build)
            cached <- Abort.recover[FileException](_ => false)(Path.runReadOnly(exec.exists))
            _ <-
                if cached then Kyo.unit
                else download(build, v, platform, versionDir)
        yield exec.toString
        end for
    end ensureWith

    // --- Internal ---

    private def platformCode(using Frame): String < (Sync & Abort[BrowserSetupException]) =
        for
            os   <- System.operatingSystem
            arch <- System.architecture
            code <- resolvePlatform(os, arch)
        yield code

    private[kyo] def resolvePlatform(os: System.OS, arch: System.Arch)(using
        Frame
    )
        : String < Abort[BrowserSetupException] =
        (os, arch) match
            case (System.OS.MacOS, System.Arch.Aarch64)  => "mac-arm64"
            case (System.OS.MacOS, System.Arch.X86_64)   => "mac-x64"
            case (System.OS.Linux, System.Arch.X86_64)   => "linux64"
            case (System.OS.Windows, System.Arch.X86_64) => "win64"
            case (System.OS.Windows, System.Arch.X86)    => "win32"
            case other =>
                Abort.fail[BrowserSetupException](
                    BrowserSetupFailedException(unsupportedPlatformMessage(other))
                )
        end match
    end resolvePlatform

    /** Error text shown when [[resolvePlatform]] aborts. Includes install instructions so end users (and CI logs) can act without consulting
      * the docs. The Linux ARM clause names the apt package because it is the most common encounter today (CI runners, Raspberry Pi);
      * Windows ARM falls under the generic message.
      */
    private[internal] def unsupportedPlatformMessage(tuple: (System.OS, System.Arch)): String =
        val osArch = tuple match
            case (os, arch) => s"$os/$arch"
        val hint = tuple match
            case (System.OS.Linux, System.Arch.Aarch64) | (System.OS.Linux, System.Arch.Arm) =>
                "Google publishes no chrome-headless-shell for linux-arm64. " +
                    "Install Chromium via your package manager (e.g. `apt install chromium-browser` on Debian/Ubuntu) and " +
                    "pass `Browser.LaunchConfig.chromium(\"chromium-browser\")` to `Browser.run(config) { ... }` instead of the zero-arg overload."
            case _ =>
                "Chrome-for-Testing does not publish a chrome-headless-shell binary for this platform. " +
                    "Install Chrome (or a Chromium build) manually and pass `Browser.LaunchConfig.chrome(<path>)` " +
                    "(or `Browser.LaunchConfig.chromium(<path>)`) to `Browser.run(config) { ... }` instead of the zero-arg overload."
        s"kyo-browser cannot auto-download chrome-headless-shell for $osArch. $hint"
    end unsupportedPlatformMessage

    private[kyo] def cacheRoot(using Frame): Path < Sync =
        System.env[String]("KYO_BROWSER_CACHE").map {
            case Present(p) => Path(p)
            case Absent     => Path.basePaths.cache / "kyo-browser"
        }

    /** Wire-level artifact name as published by Chrome-for-Testing (`chrome-headless-shell` or `chrome`). Used in the cache directory
      * prefix, the download URL, and the extracted inner directory name (all three follow the same `{artifact}-{platform}` convention).
      */
    private[kyo] def artifactName(build: Browser.ChromeForTestingBuild): String = build match
        case Browser.ChromeForTestingBuild.HeadlessShell => "chrome-headless-shell"
        case Browser.ChromeForTestingBuild.Chrome        => "chrome"

    private[kyo] def executablePath(versionDir: Path, platform: String, build: Browser.ChromeForTestingBuild)(using Frame): Path =
        val inner = versionDir / s"${artifactName(build)}-$platform"
        build match
            case Browser.ChromeForTestingBuild.HeadlessShell =>
                platform match
                    case "win64" | "win32" => inner / "chrome-headless-shell.exe"
                    case _                 => inner / "chrome-headless-shell"
            case Browser.ChromeForTestingBuild.Chrome =>
                platform match
                    case "mac-arm64" | "mac-x64" =>
                        // The macOS full-chrome zip extracts to `chrome-{platform}/Google Chrome for Testing.app/...` with the actual
                        // executable nested inside the `.app` bundle. Linux/Windows extract a flat layout.
                        inner / "Google Chrome for Testing.app" / "Contents" / "MacOS" / "Google Chrome for Testing"
                    case "win64" | "win32" => inner / "chrome.exe"
                    case _                 => inner / "chrome"
        end match
    end executablePath

    private def downloadAndExtract(
        build: Browser.ChromeForTestingBuild,
        version: String,
        platform: String,
        versionDir: Path,
        downloadTimeout: Duration
    )(using Frame): Unit < (Async & Abort[BrowserSetupException]) =
        val artifact = artifactName(build)
        val url      = s"https://storage.googleapis.com/chrome-for-testing-public/$version/$platform/$artifact-$platform.zip"
        Scope.run {
            for
                tmpDir <- createTempDir
                zip = tmpDir / s"$artifact-$platform.zip"
                _ <- downloadZip(url, zip, downloadTimeout)
                _ <- extractZip(zip, versionDir)
                _ <- makeExecutable(executablePath(versionDir, platform, build))
            yield ()
        }
    end downloadAndExtract

    private def createTempDir(using Frame): Path < (Scope & Sync & Abort[BrowserSetupException]) =
        Abort.recover[FileException] { (ex: FileException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome download temp dir", ex)
            )
        } {
            Path.run(Path.tempDir("kyo-browser-dl-"))
        }

    private[kyo] def downloadZip(url: String, dest: Path, downloadTimeout: Duration)(using
        Frame
    )
        : Unit < (Async & Abort[BrowserSetupException]) =
        Abort.recover[HttpException | FileException] { (ex) =>
            val cause: Throwable = ex match
                case e: HttpException => e
                case e: FileException => e
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(s"failed to download Chrome from $url", cause)
            )
        } {
            HttpClient.withConfig(_.timeout(downloadTimeout)) {
                // Stream the archive straight to disk rather than buffering it. `getBinary` reads the whole body into
                // memory bounded by `HttpClientConfig.maxResponseLength` (100 MiB), which rejects the full-Chrome zip
                // (~200 MiB). `getStreamBytes` has no such cap; `writeTo` sinks each network chunk through a scoped write
                // handle and removes the partial file if the download fails midway.
                Scope.run {
                    HttpClient.getStreamBytes(url)
                        .mapChunkPure(_.map(span => Chunk.from(span.toArray)).flattenChunk)
                        .writeTo(dest)
                }
            }
        }

    private[kyo] def extractZip(archive: Path, dest: Path)(using
        Frame
    )
        : Unit < (Async & Abort[BrowserSetupException]) =
        for
            os <- System.operatingSystem
            _  <- createDir(dest)
            // Windows 10+ ships `tar` that handles zip. Unix has `unzip`.
            cmd =
                if os == System.OS.Windows then Command("tar", "-xf", archive.toString, "-C", dest.toString)
                else Command("unzip", "-q", archive.toString, "-d", dest.toString)
            _ <- Abort.recover[CommandException | Process.ExitCode] { err =>
                failSetup(s"failed to extract $archive → $dest: $err", err)
            } {
                cmd.waitForSuccess
            }
        yield ()

    private def createDir(dir: Path)(using Frame): Unit < (Sync & Abort[BrowserSetupException]) =
        Abort.recover[FileException] { (ex: FileException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(s"failed to create dir $dir", ex)
            )
        } {
            Path.run(dir.mkDir)
        }

    private def makeExecutable(exec: Path)(using Frame): Unit < (Async & Abort[BrowserSetupException]) =
        for
            os <- System.operatingSystem
            // Windows doesn't use chmod; the bit is baked into the PE header.
            _ <-
                if os == System.OS.Windows then Kyo.unit
                else
                    Abort.recover[CommandException | Process.ExitCode] { err =>
                        failSetup(s"failed to chmod +x $exec: $err", err)
                    } {
                        Command("chmod", "+x", exec.toString).waitForSuccess
                    }
        yield ()

    private def asThrowable(err: CommandException | Process.ExitCode): Maybe[Throwable] =
        err match
            case t: Throwable => Present(t)
            case _            => Absent

    private def failSetup[A](message: String, err: CommandException | Process.ExitCode)(using
        Frame
    )
        : A < Abort[BrowserSetupException] =
        Abort.fail(BrowserSetupFailedException(message, asThrowable(err)))

end ChromeDownloader

/** Wire shape of a single channel entry in `last-known-good-versions.json`. Only `version` is consumed; other fields (`channel`,
  * `revision`) are present in the response but unused, so the case class declares only what we read. kyo-schema's case-class decoder is
  * permissive about unknown fields.
  */
final private[internal] case class ChromeChannelInfo(version: String) derives Schema

/** The `channels` map from the metadata endpoint. Only the `Stable` channel is consumed; Beta / Dev / Canary are present in the response.
  */
final private[internal] case class ChromeChannels(Stable: ChromeChannelInfo) derives Schema

/** Top-level shape of `https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions.json`. */
final private[internal] case class ChromeForTestingMetadata(channels: ChromeChannels) derives Schema
