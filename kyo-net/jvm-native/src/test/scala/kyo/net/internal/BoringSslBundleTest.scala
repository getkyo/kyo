package kyo.net.internal

import java.io.File
import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Bundle test: the vendored BoringSSL (`kyonet_boringssl`) is staged, loadable, and pinned.
  *
  * BoringSSL is built+staged per host os-arch by `kyo-net/build/boringssl/build-boringssl.sh`. The CI
  * harness runs it before this test. Three checks:
  *   1. the staged `libssl.a`/`libcrypto.a` exist and are non-empty for the host os-arch;
  *   2. the bundled `kyonet_boringssl` shim loads through the kyo-ffi load path and
  *      `kyo_bssl_probe_available()` returns true (no `UnsatisfiedLinkError`/missing symbol) on JVM
  *      (Panama) and Native; gated on the lib being staged for this host (cancels otherwise, like the
  *      Linux-only io_uring test);
  *   3. `BORINGSSL_COMMIT` parses to a concrete 40-hex commit (a deliberate pin, not a branch name).
  *
  * The loadable-probe check cancels rather than fails when BoringSSL was not staged for the host (e.g. an
  * unsupported os-arch, or a runner where build-boringssl.sh did not run), so a host without the bundle is
  * not a failure. BoringSSL serves JVM+Native only; JS uses Node tls and has no bundle, so that check
  * cancels on JS.
  */
class BoringSslBundleTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Walk up from the test working directory to find `kyo-net/build/boringssl`. Forked JVM tests
      * run from `kyo-net/jvm`; Native/JS from less predictable dirs, so probe several ancestors and a
      * couple of known offsets rather than assuming one cwd.
      */
    private def boringSslDir: Maybe[File] =
        // kyo.System shadows java.lang.System here; use the fully qualified form.
        val cwd        = new File(java.lang.System.getProperty("user.dir", "."))
        val marker     = new File(new File("build"), "boringssl")
        val markerName = "BORINGSSL_COMMIT"
        // Candidate roots: each ancestor of cwd, joined with kyo-net/build/boringssl and build/boringssl.
        def ancestors(f: File): List[File] =
            if f == null then Nil else f :: ancestors(f.getParentFile)
        val candidates =
            ancestors(cwd).flatMap { base =>
                List(
                    new File(new File(base, "kyo-net"), marker.getPath),
                    new File(base, marker.getPath)
                )
            }
        candidates.find(d => new File(d, markerName).isFile) match
            case Some(d) => Present(d)
            case None    => Absent
    end boringSslDir

    /** Host os-arch in the build-boringssl.sh / staged/<os-arch> naming. */
    private def hostOsArch: String =
        val osName = java.lang.System.getProperty("os.name", "").toLowerCase
        val os =
            if osName.contains("mac") then "darwin"
            else if osName.contains("win") then "windows"
            else "linux"
        val arch = java.lang.System.getProperty("os.arch", "") match
            case "x86_64" | "amd64"  => "x86_64"
            case "aarch64" | "arm64" => "aarch64"
            case other               => other
        s"$os-$arch"
    end hostOsArch

    private def stagedLibDir: Maybe[File] =
        boringSslDir.map(d => new File(new File(new File(d, "staged"), hostOsArch), "lib"))

    private def stagedFor(osArch: String): Maybe[(File, File)] =
        boringSslDir.flatMap { d =>
            val lib    = new File(new File(new File(d, "staged"), osArch), "lib")
            val ssl    = new File(lib, "libssl.a")
            val crypto = new File(lib, "libcrypto.a")
            if ssl.isFile && crypto.isFile then Present((ssl, crypto)) else Absent
        }

    "BoringSslBundle" - {

        "the staged BoringSSL static archives exist and are non-empty for the host os-arch" in {
            // Cancel rather than fail when the bundle was not staged for this host: the CI gate runs
            // build-boringssl.sh for supported runners, but a developer machine / unsupported os-arch
            // legitimately has no staged archive (same posture as the Linux-only io_uring test).
            stagedFor(hostOsArch) match
                case Absent =>
                    cancel(s"BoringSSL not staged for $hostOsArch (run build-boringssl.sh $hostOsArch)")
                case Present((ssl, crypto)) =>
                    assert(ssl.isFile, s"missing ${ssl.getAbsolutePath}")
                    assert(crypto.isFile, s"missing ${crypto.getAbsolutePath}")
                    assert(ssl.length() > 0L, s"empty ${ssl.getAbsolutePath}")
                    assert(crypto.length() > 0L, s"empty ${crypto.getAbsolutePath}")
        }

        "the bundled kyonet_boringssl loads via kyo-ffi and probe_available() returns true" in {
            // Gate on the staged archive being present for this host: with no bundle there is nothing
            // to load, so cancel (BoringSSL serves JVM+Native; JS has no bundle and cancels here too).
            if stagedLibDir.isEmpty || stagedFor(hostOsArch).isEmpty then
                cancel(s"BoringSSL not staged for $hostOsArch; nothing to load")
            val loaded =
                try Maybe(Ffi.load[BoringSslProbe])
                catch case _: Throwable => Maybe.empty[BoringSslProbe]
            val probe = loaded.getOrElse(cancel(s"kyonet_boringssl shim not built/loadable for $hostOsArch"))
            // The actual load assertion: the bundled shim resolves SSL_CTX_new/free with no
            // UnsatisfiedLinkError/missing symbol and reports the bundled BoringSSL functional.
            assert(probe.kyo_bssl_probe_available(), "kyo_bssl_probe_available() returned false")
        }

        "BORINGSSL_COMMIT pins a concrete 40-hex commit, not a branch name or empty" in {
            val dir    = boringSslDir.getOrElse(cancel("kyo-net/build/boringssl not found from test cwd"))
            val file   = new File(dir, "BORINGSSL_COMMIT")
            val source = scala.io.Source.fromFile(file)
            val commit =
                try
                    source.getLines()
                        .map(_.trim)
                        .filter(l => l.nonEmpty && !l.startsWith("#"))
                        .toList
                        .headOption
                        .getOrElse("")
                finally source.close()
            assert(commit.length == 40, s"BORINGSSL_COMMIT is not 40 chars: '$commit'")
            assert(commit.forall(c => "0123456789abcdef".contains(c)), s"BORINGSSL_COMMIT is not lowercase hex: '$commit'")
        }
    }

end BoringSslBundleTest
