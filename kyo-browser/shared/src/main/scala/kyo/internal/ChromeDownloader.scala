package kyo.internal

import kyo.*

/** Downloads and caches Chrome for Testing binaries on demand.
  *
  * The first call downloads the requested Chrome version to a user-local cache; subsequent calls reuse the cached executable. Downloads
  * come from Google's official Chrome-for-Testing archive (`https://storage.googleapis.com/chrome-for-testing-public/`).
  *
  * The cache directory defaults to `{Path.basePaths.cache}/kyo-browser/`. The `KYO_BROWSER_CACHE` environment variable overrides it.
  *
  * The platform code (`mac-arm64`, `mac-x64`, `linux64`, `win64`, `win32`) is derived from `kyo.System.operatingSystem` and
  * `kyo.System.architecture`.
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
      * supplied `cfg.metadataUrl`; `Present(v)` pins to a caller-supplied version. Safe to call repeatedly; the
      * cached binary is reused once downloaded.
      */
    def ensure(version: Maybe[String], cfg: Browser.LaunchConfig.ChromeDownloaderConfig)(using
        Frame
    ): String < (Async & Abort[BrowserSetupException]) =
        ensureWith(version, cfg, downloadAndExtract(_, _, _, cfg.downloadTimeout))

    /** Test seam: same as [[ensure]] but allows the download function to be substituted. */
    def ensureWith(
        version: Maybe[String],
        cfg: Browser.LaunchConfig.ChromeDownloaderConfig,
        download: (String, String, Path) => Unit < (Async & Abort[BrowserSetupException])
    )(using Frame): String < (Async & Abort[BrowserSetupException]) =
        val resolved: String < (Async & Abort[BrowserSetupException]) =
            version.fold(latestVersion(cfg))(v => v: String)
        resolved.map { v =>
            for
                platform <- platformCode
                root     <- cacheRoot
                versionDir = root / s"chrome-$v-$platform"
                exec       = executablePath(versionDir, platform)
                cached <- exec.exists
                _ <-
                    if cached then Kyo.unit
                    else download(v, platform, versionDir)
            yield exec.toString
        }
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
                    BrowserSetupFailedException(s"Unsupported platform for Chrome-for-Testing: $other")
                )
        end match
    end resolvePlatform

    private[kyo] def cacheRoot(using Frame): Path < Sync =
        System.env[String]("KYO_BROWSER_CACHE").map {
            case Present(p) => Path(p)
            case Absent     => Path.basePaths.cache / "kyo-browser"
        }

    private[kyo] def executablePath(versionDir: Path, platform: String)(using Frame): Path =
        val inner = versionDir / s"chrome-$platform"
        platform match
            case "mac-arm64" | "mac-x64" =>
                inner / "Google Chrome for Testing.app" / "Contents" / "MacOS" / "Google Chrome for Testing"
            case "win64" | "win32" => inner / "chrome.exe"
            case _                 => inner / "chrome"
        end match
    end executablePath

    private def downloadAndExtract(version: String, platform: String, versionDir: Path, downloadTimeout: Duration)(using
        Frame
    )
        : Unit < (Async & Abort[BrowserSetupException]) =
        val url = s"https://storage.googleapis.com/chrome-for-testing-public/$version/$platform/chrome-$platform.zip"
        Scope.run {
            for
                tmpDir <- createTempDir
                zip = tmpDir / s"chrome-$platform.zip"
                _ <- downloadZip(url, zip, downloadTimeout)
                _ <- extractZip(zip, versionDir)
                _ <- makeExecutable(executablePath(versionDir, platform))
            yield ()
        }
    end downloadAndExtract

    private def createTempDir(using Frame): Path < (Scope & Sync & Abort[BrowserSetupException]) =
        Abort.recover[FileFsException] { (ex: FileFsException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome download temp dir", ex)
            )
        } {
            // `Path.tempScoped` creates a temp FILE; we need a temp DIRECTORY to drop the zip into and
            // run `unzip -d` against it. `Path.tempDir` is the directory variant; we register the
            // recursive removal via `Scope.ensure` so the directory plus its contents are torn down
            // on scope exit (success, abort, or fiber interrupt).
            Path.tempDir("kyo-browser-dl-").map { p =>
                Scope.ensure(Abort.run[FileFsException](p.removeAll).unit).andThen(p)
            }
        }

    private[kyo] def downloadZip(url: String, dest: Path, downloadTimeout: Duration)(using
        Frame
    )
        : Unit < (Async & Abort[BrowserSetupException]) =
        Abort.recover[HttpException | FileWriteException] { (ex) =>
            val cause: Throwable = ex match
                case e: HttpException      => e
                case e: FileWriteException => e
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(s"failed to download Chrome from $url", cause)
            )
        } {
            HttpClient.withConfig(_.timeout(downloadTimeout)) {
                HttpClient.getBinary(url).map(bytes => dest.writeBytes(bytes))
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
        Abort.recover[FileFsException] { (ex: FileFsException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(s"failed to create dir $dir", ex)
            )
        } {
            dir.mkDir
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
