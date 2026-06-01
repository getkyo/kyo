package kyo.internal

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.collection.mutable

/** Shared classpath fixtures for kyo-tasty decoder-fidelity-2 tests.
  *
  * Extends TestClasspaths with a warning-capture sink (withWarningSink) and a singleton standard classpath that is loaded once per JVM for
  * the fidelity-2 test suite. All loading uses ErrorMode.SoftFail.
  *
  * The withWarningSink helper wires a capturing Log.Unsafe into the fiber-local Log before classpath loading so that TypeUnpickler warning
  * messages can be counted and asserted on without affecting other test fibers. This is fiber-local (not global), so concurrent tests do not
  * interfere.
  *
  * Per HARD RULE 1: real-classpath fixtures only; no synthetic data in this file.
  */
private[kyo] object TestClasspaths2:

    /** A captured batch of log warnings from a single classpath load. */
    final case class WarningSink(messages: Seq[String]):
        def countMatching(needle: String): Int = messages.count(_.contains(needle))
        def unknownTagCount: Int               = countMatching("unhandled cat") + countMatching("unknown TASTy type tag")
    end WarningSink

    /** Execute f with a warning-capturing Log installed for the current fiber.
      *
      * The returned WarningSink collects every warn() call that occurs during f's execution. This is fiber-local via Log.let so parallel test
      * fibers do not see each other's log output.
      */
    def withWarningSink[A, S](f: WarningSink => A < S)(using Frame): A < S =
        import AllowUnsafe.embrace.danger
        val buf = mutable.ArrayBuffer.empty[String]
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sinkLogger
        Log.let(Log(sinkLogger)) {
            f(WarningSink(buf.toSeq))
        }
    end withWarningSink

    /** The standard 3-root combo: kyo-tasty + kyo-data + scala-library (same as TestClasspaths.standard). */
    def standardRoots: Seq[String] = TestClasspaths.standard

    /** Load the standard classpath (kyo-tasty + kyo-data + scala-library) with a warning sink.
      *
      * Returns both the classpath and the warning sink so callers can assert on both.
      */
    def loadStandardWithSink(using Frame): (Tasty.Classpath, WarningSink) < (Async & Scope & Abort[TastyError]) =
        import AllowUnsafe.embrace.danger
        val buf = mutable.ArrayBuffer.empty[String]
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                val m = msg; buf.synchronized { discard(buf += m) }
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sinkLogger
        Log.let(Log(sinkLogger)) {
            TestClasspaths.withClasspath(standardRoots).map: cp =>
                (cp, WarningSink(buf.toSeq))
        }
    end loadStandardWithSink

    /** Subset: the kyo-tasty compiled classes directory or jar from the test classpath (same subset as TestClasspaths.kyoTasty). */
    def kyoTastyRoots: Seq[String] = TestClasspaths.kyoTasty

    /** Perform a cold load then write a snapshot to a temp dir and read it back, returning (cold, warm).
      *
      * Used by SnapshotFidelity2Test for INV-013 and INV-101-DF2 assertions. Both classpaths are fully
      * loaded before the tuple is returned; callers can compare any field.
      */
    def standardWithSnapshot(
        roots: Seq[String] = standardRoots
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths.withClasspath(roots).flatMap: coldCp =>
            Sync.defer:
                java.nio.file.Files.createTempDirectory("kyo-df2-snapshot").toString
            .flatMap: tmpDir =>
                val digest  = Array[Byte](0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
                val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
                kyo.internal.tasty.snapshot.SnapshotWriter.write(coldCp, tmpDir, digest, platSrc).flatMap: _ =>
                    val hexDigest    = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
                    val snapshotPath = s"$tmpDir/$hexDigest.krfl"
                    kyo.internal.tasty.snapshot.SnapshotReader.read(snapshotPath, platSrc).map: warmCp =>
                        (coldCp, warmCp)

    /** Perform two independent cold loads and write each to a fresh snapshot, returning both byte arrays.
      *
      * Used by SnapshotFidelity2Test for the byte-equality idempotency check (F-A4-005). Each load is
      * completely independent: separate Classpath instances, separate temp directories, same input roots.
      *
      * Uses concurrency=1 to ensure deterministic symbol ordering. The parallel multi-file decoder
      * produces non-deterministic file processing order, which would change symbol indices between runs.
      * With a single decoder the file processing order is determined solely by the file walker, which is
      * stable for the same roots (directory listing order is stable on all supported platforms).
      */
    def twoColdInits(
        roots: Seq[String] = standardRoots
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        val digest  = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
        kyo.internal.tasty.query.ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, platSrc, 1).flatMap: cp1 =>
            Sync.defer:
                java.nio.file.Files.createTempDirectory("kyo-df2-snap-a").toString
            .flatMap: tmpA =>
                kyo.internal.tasty.snapshot.SnapshotWriter.write(cp1, tmpA, digest, platSrc).flatMap: _ =>
                    val hexA  = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
                    val pathA = s"$tmpA/$hexA.krfl"
                    kyo.internal.tasty.query.ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, platSrc, 1).flatMap: cp2 =>
                        Sync.defer:
                            java.nio.file.Files.createTempDirectory("kyo-df2-snap-b").toString
                        .flatMap: tmpB =>
                            val digest2 = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
                            kyo.internal.tasty.snapshot.SnapshotWriter.write(cp2, tmpB, digest2, platSrc).flatMap: _ =>
                                val hexB  = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest2)
                                val pathB = s"$tmpB/$hexB.krfl"
                                platSrc.read(pathA).flatMap: bytesA =>
                                    platSrc.read(pathB).map: bytesB =>
                                        (bytesA, bytesB)
    end twoColdInits

end TestClasspaths2
