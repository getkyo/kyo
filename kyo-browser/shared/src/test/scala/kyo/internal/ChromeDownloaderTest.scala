package kyo.internal

import kyo.*
import kyo.System.Arch
import kyo.System.OS
import kyo.System.Parser

/** Pure + per-platform tests for [[ChromeDownloader]].
  *
  * No real Chrome download is performed; scenarios use fast-fail URLs, in-process tempdirs, and a custom `System` to override
  * `KYO_BROWSER_CACHE` / OS / arch where needed.
  */
class ChromeDownloaderTest extends Test:

    // ---- helpers ----

    /** Fake `System` used across ChromeDownloader tests to control `KYO_BROWSER_CACHE`, OS, and arch. */
    private def fakeSystem(
        envOverrides: Map[String, String],
        os: OS,
        arch: Arch
    ): System =
        new System:
            // ChromeDownloader only uses env/operatingSystem/architecture, never the low-level
            // Unsafe surface, so delegating to the live System's Unsafe is a no-op for these tests.
            def unsafe: System.Unsafe = System.live.unsafe
            def env[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & Sync) =
                Sync.defer(envOverrides.get(name) match
                    case Some(v) => Abort.get(p(v).map(Maybe(_)))
                    case None    => Maybe.empty[A])
            def property[E, A](n: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & Sync) =
                Sync.defer(Maybe.empty[A])
            def lineSeparator(using Frame): String < Sync    = Sync.defer("\n")
            def userName(using Frame): String < Sync         = Sync.defer("test")
            def operatingSystem(using Frame): OS < Sync      = Sync.defer(os)
            def architecture(using Frame): Arch < Sync       = Sync.defer(arch)
            def availableProcessors(using Frame): Int < Sync = Sync.defer(1)

    // ---- latestVersion shape ----

    "ChromeDownloader.latestVersion resolves to a non-empty dotted-numeric version" in run {
        // Hits the live Chrome-for-Testing metadata endpoint. On offline runners the call falls through to the
        // hardcoded backstop, which still satisfies the format check, so the test stays green either way.
        Abort.run[BrowserSetupException](ChromeDownloader.latestVersion(Browser.LaunchConfig.default.chromeDownloaderConfig)).map {
            case Result.Success(v) =>
                assert(v.nonEmpty, s"latestVersion is empty")
                assert(v.matches("""^\d+(\.\d+)+$"""), s"latestVersion '$v' does not match dotted-numeric format")
            case other => fail(s"latestVersion lookup failed: $other")
        }
    }

    // ---- version override path is reflected in the cached directory ----

    /** Creates a temp directory that auto-deletes when the enclosing scope closes. */
    private def tempDirScoped(prefix: String)(using Frame): Path < (Scope & Sync & Abort[FileFsException]) =
        Path.tempDir(prefix).map { p =>
            Scope.acquireRelease(p) { dir =>
                Abort.run[FileFsException](dir.removeAll).unit
            }
        }

    /** Builds a [[System]] override whose env returns `KYO_BROWSER_CACHE = cacheDir.unsafe.show`. OS/arch fall back to the host. */
    private def systemWithCache(cacheDir: Path)(os: OS, arch: Arch): System =
        fakeSystem(envOverrides = Map("KYO_BROWSER_CACHE" -> cacheDir.unsafe.show), os = os, arch = arch)

    "ensure(version) resolves a cacheDir that embeds the requested version (no download required)" in run {
        Scope.run {
            tempDirScoped("kyo-cd-vover-").map { tmp =>
                // Pre-create a fake executable for whichever platform this host is on so that `ensure` short-circuits
                // (no network access).
                for
                    os       <- System.operatingSystem
                    arch     <- System.architecture
                    platform <- ChromeDownloader.resolvePlatform(os, arch)
                    customVersion = "999.0.1234.567"
                    versionDir    = tmp / s"chrome-headless-shell-$customVersion-$platform"
                    exec          = ChromeDownloader.executablePath(versionDir, platform)
                    _ <- exec.write("fake-exec") // createFolders=true creates the full ancestor chain
                    sys = systemWithCache(tmp)(os, arch)
                    resolved <- System.let(sys)(ChromeDownloader.ensure(
                        Present(customVersion),
                        Browser.LaunchConfig.default.chromeDownloaderConfig
                    ))
                yield
                    assert(
                        resolved.contains(s"chrome-headless-shell-$customVersion-$platform"),
                        s"resolved path '$resolved' missing version"
                    )
                    assert(resolved == exec.unsafe.show, s"resolved path '$resolved' != expected '${exec.unsafe.show}'")
                end for
            }
        }
    }

    // ---- cacheRoot honours KYO_BROWSER_CACHE env override ----

    "cacheRoot honours KYO_BROWSER_CACHE when set" in run {
        val sys = fakeSystem(
            envOverrides = Map("KYO_BROWSER_CACHE" -> "/custom/cache/dir"),
            os = OS.Linux,
            arch = Arch.X86_64
        )
        System.let(sys)(ChromeDownloader.cacheRoot).map { root =>
            assert(root.unsafe.show == "/custom/cache/dir", s"got '${root.unsafe.show}'")
        }
    }

    "cacheRoot falls back to {Path.basePaths.cache}/kyo-browser when KYO_BROWSER_CACHE is unset" in run {
        val sys = fakeSystem(envOverrides = Map.empty, os = OS.Linux, arch = Arch.X86_64)
        System.let(sys)(ChromeDownloader.cacheRoot).map { root =>
            val expected = (Path.basePaths.cache / "kyo-browser").unsafe.show
            assert(root.unsafe.show == expected, s"got '${root.unsafe.show}', expected '$expected'")
        }
    }

    // ---- resolvePlatform branches ----

    "resolvePlatform(MacOS, X86_64) == 'mac-x64'" in run {
        ChromeDownloader.resolvePlatform(OS.MacOS, Arch.X86_64).map { p =>
            assert(p == "mac-x64", s"got '$p'")
        }
    }

    "resolvePlatform(Linux, X86_64) == 'linux64'" in run {
        ChromeDownloader.resolvePlatform(OS.Linux, Arch.X86_64).map { p =>
            assert(p == "linux64", s"got '$p'")
        }
    }

    "resolvePlatform(Windows, X86_64) == 'win64'" in run {
        ChromeDownloader.resolvePlatform(OS.Windows, Arch.X86_64).map { p =>
            assert(p == "win64", s"got '$p'")
        }
    }

    "resolvePlatform(Windows, X86) == 'win32'" in run {
        ChromeDownloader.resolvePlatform(OS.Windows, Arch.X86).map { p =>
            assert(p == "win32", s"got '$p'")
        }
    }

    "resolvePlatform(Linux, Arm) → Abort.fail with BrowserSetupFailedException" in run {
        Abort.run[BrowserSetupException](ChromeDownloader.resolvePlatform(OS.Linux, Arch.Arm)).map {
            case Result.Failure(ex: BrowserSetupFailedException) =>
                val msg = ex.getMessage
                // The error message must guide the user to the system-Chromium escape hatch; the test
                // anchors this contract so the message can't regress to a bare "unsupported".
                assert(msg.contains("cannot auto-download chrome-headless-shell"), s"missing auto-download marker: '$msg'")
                assert(msg.contains("apt install chromium-browser"), s"missing apt install hint: '$msg'")
                assert(msg.contains("LaunchConfig.chromium"), s"missing LaunchConfig.chromium pointer: '$msg'")
            case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
        }
    }

    "resolvePlatform(Linux, Aarch64) → Abort.fail with the same linux-arm guidance" in run {
        Abort.run[BrowserSetupException](ChromeDownloader.resolvePlatform(OS.Linux, Arch.Aarch64)).map {
            case Result.Failure(ex: BrowserSetupFailedException) =>
                val msg = ex.getMessage
                assert(msg.contains("apt install chromium-browser"), s"missing apt install hint: '$msg'")
            case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // ---- executablePath per-platform ----

    "executablePath for mac-arm64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "mac-arm64")
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath for mac-x64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "mac-x64")
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath for linux64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "linux64")
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath for win64 ends with 'chrome-headless-shell.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win64")
        assert(ep.name == Present("chrome-headless-shell.exe"), s"name=${ep.name}")
    }

    "executablePath for win32 ends with 'chrome-headless-shell.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win32")
        assert(ep.name == Present("chrome-headless-shell.exe"), s"name=${ep.name}")
    }

    // ---- downloadZip network failure → BrowserSetupFailedException ----

    "downloadZip from a fast-fail URL raises BrowserSetupFailedException" in run {
        // 127.0.0.1:0 is reserved as 'no port' and refuses connection immediately.
        val url = "http://127.0.0.1:0/nonexistent"
        Scope.run {
            Path.tempScoped("kyo-cd-dl-", ".zip").map { dest =>
                Abort.run[BrowserSetupException](ChromeDownloader.downloadZip(url, dest, 5.minutes)).map {
                    case Result.Failure(_: BrowserSetupFailedException) => succeed
                    case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
                }
            }
        }
    }

    // ---- extractZip corrupt-zip failure ----

    "extractZip on a garbage-bytes archive raises BrowserSetupFailedException" in run {
        Scope.run {
            for
                tmp <- Path.tempScoped("kyo-cd-zip-", ".zip")
                garbage = Span[Byte](0x00.toByte, 0xff.toByte, 0x00.toByte, 0xff.toByte, 0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
                _ <- tmp.writeBytes(garbage)
                dest = Path(tmp.unsafe.show + "-extract")
                result <- Abort.run[BrowserSetupException](ChromeDownloader.extractZip(tmp, dest))
            yield result match
                case Result.Failure(_: BrowserSetupFailedException) => succeed
                case other                                          => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // ---- cache-reuse proof; second ensure does not invoke the downloader ----

    "ensure() reuses the cached binary on the second call (downloader counter does not advance)" in run {
        Scope.run {
            tempDirScoped("kyo-cd-reuse-").map { tmp =>
                for
                    os       <- System.operatingSystem
                    arch     <- System.architecture
                    platform <- ChromeDownloader.resolvePlatform(os, arch)
                    version    = "1.2.3.4"
                    versionDir = tmp / s"chrome-headless-shell-$version-$platform"
                    exec       = ChromeDownloader.executablePath(versionDir, platform)
                    counter <- AtomicRef.init(0)
                    sys = systemWithCache(tmp)(os, arch)
                    // Counter-tracking downloader: increments on every invocation, then materialises the
                    // executable so subsequent calls find it cached.
                    fakeDownload: ((String, String, Path) => Unit < (Async & Abort[BrowserSetupException])) =
                        (_v, _p, vDir) =>
                            val target = ChromeDownloader.executablePath(vDir, _p)
                            for
                                _ <- counter.updateAndGet(_ + 1)
                                _ <- Abort.run[FileFsException](target.parent match
                                    case Present(par) => par.mkDir
                                    case Absent       => Kyo.unit).map(_.getOrThrow)
                                _ <- Abort.run[FileWriteException](target.write("fake-exec")).map(_.getOrThrow)
                            yield ()
                            end for
                    first <- System.let(sys)(ChromeDownloader.ensureWith(
                        Present(version),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        fakeDownload
                    ))
                    after1 <- counter.get
                    second <- System.let(sys)(ChromeDownloader.ensureWith(
                        Present(version),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        fakeDownload
                    ))
                    after2 <- counter.get
                yield
                    assert(first == exec.unsafe.show, s"first resolved '$first' != expected '${exec.unsafe.show}'")
                    assert(second == first, s"second resolved '$second' != first '$first'")
                    assert(after1 == 1, s"counter after first ensure: $after1 (expected 1)")
                    assert(after2 == 1, s"counter after second ensure: $after2 (expected 1 - cache reuse violated)")
                end for
            }
        }
    }

end ChromeDownloaderTest
