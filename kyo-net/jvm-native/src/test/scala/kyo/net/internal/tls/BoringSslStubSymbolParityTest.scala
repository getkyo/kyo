package kyo.net.internal.tls

import java.io.File
import kyo.*
import kyo.net.Test

/** Verifies that the BoringSSL stub exports exactly the real shim's `kyo_bssl_*` symbol set.
  *
  * When the staged BoringSSL archives are absent for a host, the build links `kyo_net_boringssl_stub.c` instead of `kyo_net_boringssl.c`. The
  * stub must DEFINE the same `kyo_bssl_*` surface (each as an "unavailable" no-op) so the binding's `@extern`/Panama symbols resolve and only
  * `probe_available` reports false. If the real shim grows an export the stub lacks, an unstaged host fails to link; this test catches that
  * drift as pure source-file set equality, no compile, no timing.
  *
  * The test-only error-injection seams (`kyo_bssl_test_*`) are excluded: they exist for the C-shim reproduction tests and are not part of the
  * production surface the binding requires, so they are not held to real-vs-stub parity.
  */
class BoringSslStubSymbolParityTest extends Test:

    private val testOnlyExports = Set("kyo_bssl_test_put_error", "kyo_bssl_test_break_write_bio")

    /** Locate the c-boringssl source directory by walking up from the test working directory, mirroring BoringSslBundleTest. */
    private def cBoringSslDir: Maybe[File] =
        val cwd = new File(java.lang.System.getProperty("user.dir", "."))
        val rel = new File(new File(new File(new File("shared"), "src"), "main"), "c-boringssl")
        def ancestors(f: File): List[File] =
            if f == null then Nil else f :: ancestors(f.getParentFile)
        val candidates =
            ancestors(cwd).flatMap { base =>
                List(
                    new File(new File(base, "kyo-net"), rel.getPath),
                    new File(base, rel.getPath)
                )
            }
        candidates.find(d => new File(d, "kyo_net_boringssl.c").isFile) match
            case Some(d) => Present(d)
            case None    => Absent
    end cBoringSslDir

    /** Extract every `kyo_bssl_*` function name DEFINED in a C source file (a return type, the name, then `(`, at column 0). */
    private def exportedSymbols(source: String): Set[String] =
        val defPattern = """(?m)^(?:long|int|void|const char\s*\*|unsigned[\w ]*)\s+(kyo_bssl_[a-z0-9_]+)\s*\(""".r
        defPattern.findAllMatchIn(source).map(_.group(1)).toSet

    private def read(f: File): String =
        val src = scala.io.Source.fromFile(f, "UTF-8")
        try src.mkString
        finally src.close()
    end read

    "the stub exports exactly the real shim's kyo_bssl_* symbol set (excluding the test-only seams)" in {
        val dir      = cBoringSslDir.getOrElse(cancel("c-boringssl source dir not found from the test working directory"))
        val stubDir  = new File(dir.getParentFile, "c-boringssl-stub")
        val realFile = new File(dir, "kyo_net_boringssl.c")
        val stubFile = new File(stubDir, "kyo_net_boringssl_stub.c")
        assert(realFile.isFile, s"real shim not found: ${realFile.getAbsolutePath}")
        assert(stubFile.isFile, s"stub shim not found: ${stubFile.getAbsolutePath}")

        val realSyms = exportedSymbols(read(realFile)) -- testOnlyExports
        val stubSyms = exportedSymbols(read(stubFile)) -- testOnlyExports

        assert(realSyms.nonEmpty, "extracted no kyo_bssl_* symbols from the real shim; the extraction pattern is wrong")
        val onlyReal = realSyms -- stubSyms
        val onlyStub = stubSyms -- realSyms
        assert(
            realSyms == stubSyms,
            s"real-vs-stub kyo_bssl_* symbol drift: only in real=${onlyReal.toList.sorted}, only in stub=${onlyStub.toList.sorted}"
        )
    }

end BoringSslStubSymbolParityTest
