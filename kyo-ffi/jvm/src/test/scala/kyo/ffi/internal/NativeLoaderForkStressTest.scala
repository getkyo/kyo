package kyo.ffi.internal

import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.Test
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Fork-N-JVMs extraction stress.
  *
  * Spawns `N` child JVMs that all race to extract the same content-hashed payload into a shared extraction directory. Asserts:
  *   1. Every child exits `0` (no `UnsatisfiedLinkError`, no extraction hang).
  *   2. Every child prints the same final extracted path.
  *   3. The final extracted file's byte content matches the payload exactly (atomic-rename guarantees no partial-write residue).
  *
  * The stale-lock unit test in [[NativeLoaderConcurrencySpec]] is the primary regression coverage; this spec is expensive per-run but
  * exercises the real multi-process path. Fork count is 4 by default; drops to 2 on slow hosts via `-Dkyo.ffi.testForkN=`.
  */
class NativeLoaderForkStressTest extends Test:

    // Resolve the JVM test classpath and a java binary so we can ProcessBuilder a child JVM that loads NativeLoaderForkMain.
    private val javaHome: Path =
        Path(java.lang.System.getProperty("java.home").nn)

    private val javaBin: Path =
        val candidate = javaHome / "bin" / "java"
        if candidate.unsafe.exists() then candidate
        else javaHome / "bin" / "java.exe" // Windows fallback; test will also cover Windows hosts.
    end javaBin

    // The test runs in-VM; java.class.path contains the test runtime classpath (prod classes + test classes + scalatest + deps).
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

    "fork N JVMs extract the same payload concurrently" in {
        val forkN   = sys.props.getOrElse("kyo.ffi.testForkN", "4").toInt
        val payload = ("F11-fork-stress-payload-" + java.util.UUID.randomUUID()).getBytes()
        val dir     = Sync.Unsafe.evalOrThrow(Path.tempDir("kyo-ffi-fork-"))
        val libId   = s"forkstress_${java.lang.System.currentTimeMillis()}"
        val hex     = hexEncode(payload)

        val pool                   = Executors.newFixedThreadPool(forkN).nn
        given ec: ExecutionContext = ExecutionContext.fromExecutorService(pool)
        try
            val futures =
                (0 until forkN).map { _ =>
                    Future {
                        val pb = new ProcessBuilder(
                            javaBin.toString,
                            "-cp",
                            classpath,
                            "kyo.ffi.internal.NativeLoaderForkMain",
                            dir.toString,
                            libId,
                            hex
                        )
                        pb.redirectErrorStream(true)
                        val proc       = pb.start().nn
                        val finishedOk = proc.waitFor(60L, TimeUnit.SECONDS)
                        val out        = new String(proc.getInputStream.nn.readAllBytes().nn).nn
                        (finishedOk, proc.exitValue(), out.trim.nn)
                    }
                }

            val results = Await.result(Future.sequence(futures), 120.seconds)

            // Child exit contract: all children must finish inside 60s, all with exit code 0.
            assert(results.map(_._1).forall(_ == true))
            assert(results.map(_._2).toSet == Set(0))
            // Every child sees the same final path (content-hashed name), proving hash agreement across JVMs.
            val reportedPaths = results.map(_._3).toSet
            assert(reportedPaths.size == 1)

            // The final extracted file exists, matches the payload byte-for-byte (atomic rename guarantee: no partial writes).
            val finalPath = Path(reportedPaths.head)
            assert(finalPath.unsafe.exists() == true)
            assert(finalPath.unsafe.read().getOrThrow == new String(payload))
            // No `.tmp-<uuid>` residue from any child; Path.Unsafe.list closes the dir stream as it collects (no leaked fd).
            val residue = dir.unsafe.list().getOrThrow.map(_.name.getOrElse("")).filter(_.contains(".tmp-")).toList
            assert(residue == Nil)
        finally
            pool.shutdownNow(): Unit
        end try
    }
end NativeLoaderForkStressTest
