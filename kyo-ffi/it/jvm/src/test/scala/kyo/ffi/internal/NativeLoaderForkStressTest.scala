package kyo.ffi.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.AllowUnsafe.embrace.danger

/** Fork-N-JVMs extraction stress.
  *
  * Spawns `N` child JVMs that all race to extract the same content-hashed payload into a shared extraction directory. Asserts:
  *   1. Every child exits `0` (no `UnsatisfiedLinkError`, no extraction hang).
  *   2. Every child prints the same final extracted path.
  *   3. The final extracted file's byte content matches the payload exactly (atomic-rename guarantees no partial-write residue).
  *
  * The stale-lock unit test in [[NativeLoaderConcurrencyTest]] is the primary regression coverage; this test also
  * exercises the real multi-process path. Fork count is 4 by default; drops to 2 on slow hosts via `-Dkyo.ffi.testForkN=`.
  */
class NativeLoaderForkStressTest extends kyo.test.Test[Any]:

    // Resolve the JVM executable and classpath for children running NativeLoaderForkMain.
    private val javaHome: Path =
        Path(java.lang.System.getProperty("java.home").nn)

    private val javaBin: Path =
        val candidate = javaHome / "bin" / "java"
        // Unsafe: executable discovery runs during test-suite setup, before any fibers start.
        if candidate.unsafe.exists().getOrThrow then candidate
        else javaHome / "bin" / "java.exe" // Windows fallback; test will also cover Windows hosts.
    end javaBin

    // java.class.path includes the production classes, test classes, and runtime dependencies.
    private val classpath: String =
        java.lang.System.getProperty("java.class.path").nn

    private def hexEncode(bytes: Array[Byte]): String =
        val sb = new StringBuilder(bytes.length * 2)
        var i  = 0
        while i < bytes.length do
            sb.append("%02x".format(bytes(i) & 0xff))
            i += 1
        sb.toString
    end hexEncode

    // This real-time timeout is a subprocess hang watchdog, not a timing assertion.
    "fork N JVMs extract the same payload concurrently".timeout(120.seconds) in {
        val forkN      = sys.props.getOrElse("kyo.ffi.testForkN", "4").toInt
        val identifier = java.util.UUID.randomUUID()
        val payload    = s"F11-fork-stress-payload-$identifier".getBytes(StandardCharsets.UTF_8)
        val libId      = s"forkstress_$identifier"
        val hex        = hexEncode(payload)
        require(forkN > 0, "kyo.ffi.testForkN must be positive")
        Path.run {
            for
                dir   <- Path.tempDir("kyo-ffi-fork-")
                start <- Latch.init(forkN)
                results <- Async.foreach(0 until forkN, forkN) { _ =>
                    start.release.andThen(start.await).andThen {
                        Command(
                            javaBin.toString,
                            "-cp",
                            classpath,
                            "kyo.ffi.internal.NativeLoaderForkMain",
                            dir.toString,
                            libId,
                            hex
                        ).redirectErrorStream(true).textWithExitCode
                    }
                }
                reportedPaths = results.map(_._1.trim).toSet
                _ =
                    assert(results.size == forkN)
                    assert(results.map(_._2).toSet == Set(ExitCode.Success))
                    assert(reportedPaths.size == 1)
                finalPath = Path(reportedPaths.head)
                exists  <- finalPath.exists
                content <- finalPath.read(StandardCharsets.UTF_8)
                entries <- dir.list
            yield
                assert(exists == true)
                assert(content == new String(payload, StandardCharsets.UTF_8))
                val residue = entries.map(_.name.getOrElse("")).filter(_.contains(".tmp-"))
                assert(residue == Chunk.empty)
        }
    }
end NativeLoaderForkStressTest
