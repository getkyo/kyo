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
class ChromeDownloaderTest extends BaseBrowserTest:

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

    "ChromeDownloader.latestVersion resolves to a non-empty dotted-numeric version" in {
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
    private def tempDirScoped(prefix: String)(using Frame): Path < (Scope & Sync & Abort[FileException]) =
        Path.run(Path.tempDir(prefix))

    /** Builds a [[System]] override whose env returns `KYO_BROWSER_CACHE = cacheDir.unsafe.show`. OS/arch fall back to the host. */
    private def systemWithCache(cacheDir: Path)(os: OS, arch: Arch): System =
        fakeSystem(envOverrides = Map("KYO_BROWSER_CACHE" -> cacheDir.unsafe.show), os = os, arch = arch)

    "ensure(version) resolves a cacheDir that embeds the requested version (no download required)" in {
        Scope.run {
            tempDirScoped("kyo-cd-vover-").map { tmp =>
                // Pre-create a fake executable for whichever platform this host is on so that `ensure` short-circuits
                // (no network access).
                for
                    os       <- System.operatingSystem
                    arch     <- System.architecture
                    platform <- ChromeDownloader.resolvePlatform(os, arch)
                    customVersion = "999.0.1234.567"
                    build         = Browser.ChromeForTestingBuild.HeadlessShell
                    versionDir    = tmp / s"chrome-headless-shell-$customVersion-$platform"
                    exec          = ChromeDownloader.executablePath(versionDir, platform, build)
                    _ <- Path.run(exec.write("fake-exec")) // createFolders=true creates the full ancestor chain
                    sys = systemWithCache(tmp)(os, arch)
                    resolved <- System.let(sys)(ChromeDownloader.ensure(
                        Present(customVersion),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        build
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

    "ensure(Chrome build) resolves a cacheDir under 'chrome-{v}-{platform}' (separate from chrome-headless-shell)" in {
        Scope.run {
            tempDirScoped("kyo-cd-fullchrome-").map { tmp =>
                for
                    os       <- System.operatingSystem
                    arch     <- System.architecture
                    platform <- ChromeDownloader.resolvePlatform(os, arch)
                    customVersion = "888.0.4444.222"
                    build         = Browser.ChromeForTestingBuild.Chrome
                    versionDir    = tmp / s"chrome-$customVersion-$platform"
                    exec          = ChromeDownloader.executablePath(versionDir, platform, build)
                    _ <- Path.run(exec.write("fake-full-chrome"))
                    sys = systemWithCache(tmp)(os, arch)
                    resolved <- System.let(sys)(ChromeDownloader.ensure(
                        Present(customVersion),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        build
                    ))
                yield
                    // The Chrome build's cache dir is `chrome-{v}-{platform}` and does NOT include the
                    // `headless-shell` segment, so two builds at the same version coexist on disk without collision.
                    assert(
                        resolved.contains(s"chrome-$customVersion-$platform"),
                        s"resolved path '$resolved' missing version"
                    )
                    assert(
                        !resolved.contains("chrome-headless-shell"),
                        s"resolved path must not point at the headless-shell variant: '$resolved'"
                    )
                    assert(resolved == exec.unsafe.show, s"resolved path '$resolved' != expected '${exec.unsafe.show}'")
                end for
            }
        }
    }

    // ---- cacheRoot honours KYO_BROWSER_CACHE env override ----

    "cacheRoot honours KYO_BROWSER_CACHE when set" in {
        val sys = fakeSystem(
            envOverrides = Map("KYO_BROWSER_CACHE" -> "/custom/cache/dir"),
            os = OS.Linux,
            arch = Arch.X86_64
        )
        System.let(sys)(ChromeDownloader.cacheRoot).map { root =>
            assert(root.unsafe.show == "/custom/cache/dir", s"got '${root.unsafe.show}'")
        }
    }

    "cacheRoot falls back to {Path.basePaths.cache}/kyo-browser when KYO_BROWSER_CACHE is unset" in {
        val sys = fakeSystem(envOverrides = Map.empty, os = OS.Linux, arch = Arch.X86_64)
        System.let(sys)(ChromeDownloader.cacheRoot).map { root =>
            val expected = (Path.basePaths.cache / "kyo-browser").unsafe.show
            assert(root.unsafe.show == expected, s"got '${root.unsafe.show}', expected '$expected'")
        }
    }

    // ---- resolvePlatform branches ----

    "resolvePlatform(MacOS, X86_64) == 'mac-x64'" in {
        ChromeDownloader.resolvePlatform(OS.MacOS, Arch.X86_64).map { p =>
            assert(p == "mac-x64", s"got '$p'")
        }
    }

    "resolvePlatform(Linux, X86_64) == 'linux64'" in {
        ChromeDownloader.resolvePlatform(OS.Linux, Arch.X86_64).map { p =>
            assert(p == "linux64", s"got '$p'")
        }
    }

    "resolvePlatform(Windows, X86_64) == 'win64'" in {
        ChromeDownloader.resolvePlatform(OS.Windows, Arch.X86_64).map { p =>
            assert(p == "win64", s"got '$p'")
        }
    }

    "resolvePlatform(Windows, X86) == 'win32'" in {
        ChromeDownloader.resolvePlatform(OS.Windows, Arch.X86).map { p =>
            assert(p == "win32", s"got '$p'")
        }
    }

    "resolvePlatform(Linux, Arm) → Abort.fail with BrowserSetupFailedException" in {
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

    "resolvePlatform(Linux, Aarch64) → Abort.fail with the same linux-arm guidance" in {
        Abort.run[BrowserSetupException](ChromeDownloader.resolvePlatform(OS.Linux, Arch.Aarch64)).map {
            case Result.Failure(ex: BrowserSetupFailedException) =>
                val msg = ex.getMessage
                assert(msg.contains("apt install chromium-browser"), s"missing apt install hint: '$msg'")
            case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // ---- executablePath per-platform: HeadlessShell ----

    private val headlessShell = Browser.ChromeForTestingBuild.HeadlessShell
    private val fullChrome    = Browser.ChromeForTestingBuild.Chrome

    "executablePath HeadlessShell mac-arm64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "mac-arm64", headlessShell)
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath HeadlessShell mac-x64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "mac-x64", headlessShell)
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath HeadlessShell linux64 ends with 'chrome-headless-shell'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "linux64", headlessShell)
        assert(ep.name == Present("chrome-headless-shell"), s"name=${ep.name}")
    }

    "executablePath HeadlessShell win64 ends with 'chrome-headless-shell.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win64", headlessShell)
        assert(ep.name == Present("chrome-headless-shell.exe"), s"name=${ep.name}")
    }

    "executablePath HeadlessShell win32 ends with 'chrome-headless-shell.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win32", headlessShell)
        assert(ep.name == Present("chrome-headless-shell.exe"), s"name=${ep.name}")
    }

    // ---- executablePath per-platform: Chrome (full UI-capable build) ----

    "executablePath Chrome mac-arm64 points at the .app bundle's nested binary" in {
        // macOS chrome-for-testing chrome zip extracts to `chrome-{platform}/Google Chrome for Testing.app/...`.
        // The actual executable lives inside the .app bundle.
        val ep    = ChromeDownloader.executablePath(Path("v"), "mac-arm64", fullChrome)
        val shown = ep.unsafe.show
        assert(shown.endsWith("/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"), s"path=$shown")
        assert(shown.contains("/chrome-mac-arm64/"), s"inner dir must be chrome-mac-arm64: $shown")
    }

    "executablePath Chrome mac-x64 points at the .app bundle's nested binary" in {
        val ep    = ChromeDownloader.executablePath(Path("v"), "mac-x64", fullChrome)
        val shown = ep.unsafe.show
        assert(shown.endsWith("/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"), s"path=$shown")
        assert(shown.contains("/chrome-mac-x64/"), s"inner dir must be chrome-mac-x64: $shown")
    }

    "executablePath Chrome linux64 ends with 'chrome'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "linux64", fullChrome)
        assert(ep.name == Present("chrome"), s"name=${ep.name}")
        assert(ep.unsafe.show.contains("/chrome-linux64/"), s"inner dir must be chrome-linux64: ${ep.unsafe.show}")
    }

    "executablePath Chrome win64 ends with 'chrome.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win64", fullChrome)
        assert(ep.name == Present("chrome.exe"), s"name=${ep.name}")
    }

    "executablePath Chrome win32 ends with 'chrome.exe'" in {
        val ep = ChromeDownloader.executablePath(Path("v"), "win32", fullChrome)
        assert(ep.name == Present("chrome.exe"), s"name=${ep.name}")
    }

    // ---- artifactName: wire-level mapping ----

    "artifactName(HeadlessShell) is 'chrome-headless-shell'" in {
        assert(ChromeDownloader.artifactName(headlessShell) == "chrome-headless-shell")
    }

    "artifactName(Chrome) is 'chrome'" in {
        assert(ChromeDownloader.artifactName(fullChrome) == "chrome")
    }

    // ---- downloadZip network failure → BrowserSetupFailedException ----

    "downloadZip from a fast-fail URL raises BrowserSetupFailedException" in {
        // 127.0.0.1:0 is reserved as 'no port' and refuses connection immediately.
        val url = "http://127.0.0.1:0/nonexistent"
        Scope.run {
            Path.run(Path.tempScoped("kyo-cd-dl-", ".zip")).map { dest =>
                Abort.run[BrowserSetupException](ChromeDownloader.downloadZip(url, dest, 5.minutes)).map {
                    case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("failed to download Chrome"))
                    case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
                }
            }
        }
    }

    // ---- downloadZip streams past the buffered maxResponseLength cap ----

    // Reproduce-first for the kyo-browser Chrome-download wipeout: downloadZip used HttpClient.getBinary, which reads the
    // whole response into memory bounded by HttpClientConfig.maxResponseLength (100 MiB) and rejects the full-Chrome zip
    // (~200 MiB) with HttpPayloadTooLargeException, surfaced as BrowserSetupFailedException. A tiny 64 KiB cap makes a
    // 256 KiB body reproduce that rejection deterministically without a 100 MiB fixture. The fix streams via getStreamBytes
    // (no buffered cap) and sinks each chunk to disk, so the download succeeds and the full body lands on the file even
    // though it far exceeds the cap.
    "downloadZip streams a body larger than maxResponseLength to disk instead of rejecting it" in {
        val bodySize = 256 * 1024
        val body     = Span.fromUnsafe(new Array[Byte](bodySize))
        val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
            Emit.valueWith(Chunk(body))(())
        }
        val handler = HttpRoute.getRaw("/chrome.zip").response(_.bodyStream).handler { _ =>
            HttpResponse.ok.addField("body", bodyStream).addHeader("Content-Type", "application/octet-stream")
        }
        Scope.run {
            for
                server <- HttpServer.init(0, "127.0.0.1")(handler)
                dest   <- Path.tempScoped("kyo-cd-stream-", ".zip")
                url = s"http://${server.host}:${server.port}/chrome.zip"
                // Force the buffered ceiling below the body size: getBinary would reject, the streamed path must not.
                result <- HttpClient.withConfig(_.maxResponseLength(64 * 1024)) {
                    Abort.run[BrowserSetupException](ChromeDownloader.downloadZip(url, dest, 1.minute))
                }
                size <- Path.runReadOnly(dest.size)
            yield
                assert(result.isSuccess, s"streamed download should succeed past the 64 KiB buffered cap, got: $result")
                assert(size == bodySize.toLong, s"expected the full $bodySize-byte body on disk, got $size")
            end for
        }
    }

    // ---- extractZip corrupt-zip failure ----

    "extractZip on a garbage-bytes archive raises BrowserSetupFailedException" in {
        Scope.run {
            for
                tmp <- Path.run(Path.tempScoped("kyo-cd-zip-", ".zip"))
                garbage = Span[Byte](0x00.toByte, 0xff.toByte, 0x00.toByte, 0xff.toByte, 0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
                _ <- Path.run(tmp.writeBytes(garbage))
                dest = Path(tmp.unsafe.show + "-extract")
                result <- Abort.run[BrowserSetupException](ChromeDownloader.extractZip(tmp, dest))
            yield result match
                case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("failed to extract"))
                case other => fail(s"expected Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // ---- cache-reuse proof; second ensure does not invoke the downloader ----

    "ensure() reuses the cached binary on the second call (downloader counter does not advance)" in {
        Scope.run {
            tempDirScoped("kyo-cd-reuse-").map { tmp =>
                for
                    os       <- System.operatingSystem
                    arch     <- System.architecture
                    platform <- ChromeDownloader.resolvePlatform(os, arch)
                    version    = "1.2.3.4"
                    build      = Browser.ChromeForTestingBuild.HeadlessShell
                    versionDir = tmp / s"chrome-headless-shell-$version-$platform"
                    exec       = ChromeDownloader.executablePath(versionDir, platform, build)
                    counter <- AtomicRef.init(0)
                    sys = systemWithCache(tmp)(os, arch)
                    // Counter-tracking downloader: increments on every invocation, then materialises the
                    // executable so subsequent calls find it cached.
                    fakeDownload: ((Browser.ChromeForTestingBuild, String, String, Path) => Unit < (Async & Abort[BrowserSetupException])) =
                        (_b, _v, _p, vDir) =>
                            val target = ChromeDownloader.executablePath(vDir, _p, _b)
                            for
                                _ <- counter.updateAndGet(_ + 1)
                                _ <- Abort.run[FileException](Path.run(target.parent match
                                    case Present(par) => par.mkDir
                                    case Absent       => Kyo.unit)).map(_.getOrThrow)
                                _ <- Abort.run[FileException](Path.run(target.write("fake-exec"))).map(_.getOrThrow)
                            yield ()
                            end for
                    first <- System.let(sys)(ChromeDownloader.ensureWith(
                        Present(version),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        build,
                        fakeDownload
                    ))
                    after1 <- counter.get
                    second <- System.let(sys)(ChromeDownloader.ensureWith(
                        Present(version),
                        Browser.LaunchConfig.default.chromeDownloaderConfig,
                        build,
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
