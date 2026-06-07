package kyo.internal

import kyo.*
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Cross-platform facade for fidelity test infrastructure.
  *
  * On JVM this delegates to `TestClasspaths2Platform` (provided by jvm/src/test) which wraps the real-classpath JVM implementation. On JS
  * and Native it delegates to platform-specific stubs that throw `UnsupportedOperationException` for JVM-only operations. All call sites
  * that invoke JVM-only methods are guarded by the `jvmOnly` tag or by explicit `- jvmOnly` in the test leaf, so the stubs are never
  * reached at runtime on JS/Native.
  *
  * The surface exposed here is the minimal subset required by the shared fidelity test files.
  */
private[kyo] object TestClasspaths2:

    /** The standard 3-root combo: kyo-tasty + kyo-data + scala-library (JVM only). */
    def standardRoots: Seq[String] = TestClasspaths2Platform.standardRoots

    /** Captured log warnings from a single classpath load. */
    final case class WarningSink(messages: Seq[String]):
        def countMatching(needle: String): Int = messages.count(_.contains(needle))
        def unknownTagCount: Int               = countMatching("unhandled cat") + countMatching("unknown TASTy type tag")
    end WarningSink

    /** Load the standard classpath with a warning sink (JVM only).
      *
      * Returns both the classpath and the warning sink so callers can assert on both. Callers must gate invocations with the `jvmOnly` tag.
      */
    def loadStandardWithSink(using Frame): (Tasty.Classpath, WarningSink) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.loadStandardWithSink

    /** Perform a cold load then write a snapshot to a temp dir and read it back, returning (cold, warm) (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def standardWithSnapshot(
        roots: Seq[String] = TestClasspaths2Platform.standardRoots
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.standardWithSnapshot(roots)

    /** Load the standard classpath plus java.base JDK classfiles (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def standardWithPlatformModules(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.standardWithPlatformModules

    /** Load a classpath with collisions (SoftFail) (JVM only). */
    def withCollisionClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.withCollisionClasspath

    /** Load a classpath with collisions (FailFast, expected to abort) (JVM only). */
    def withCollisionClasspathFailFast(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.withCollisionClasspathFailFast

    /** Path to a truncated .tasty file for error-mode tests (JVM only). */
    def truncatedTastyPath: String = TestClasspaths2Platform.truncatedTastyPath

    /** Path to a bit-flipped magic .tasty file for error-mode tests (JVM only). */
    def bitFlippedMagicTastyPath: String = TestClasspaths2Platform.bitFlippedMagicTastyPath

    /** Path to a mid-stream corrupted .tasty file for error-mode tests (JVM only). */
    def corruptedMidStreamTastyPath: String = TestClasspaths2Platform.corruptedMidStreamTastyPath

    /** Path to a directory containing a minimal Java-only .class file (JVM only). */
    def javaOnlyClassDir: String = TestClasspaths2Platform.javaOnlyClassDir

    /** Path to a directory containing an AP-generated .class file (JVM only). */
    def apOutputClassDir: String = TestClasspaths2Platform.apOutputClassDir

    /** Roots that simulate two Scala library versions on the same classpath (JVM only). */
    def multiVersionStdlibRoots: Seq[String] = TestClasspaths2Platform.multiVersionStdlibRoots

    /** Bytes for a KRFL file with minorVersion=3 (JVM only). */
    def v3FormatKrflBytes: Array[Byte] = TestClasspaths2Platform.v3FormatKrflBytes

    /** Load a classpath that includes kyo-core for ContextFunction coverage (JVM only).
      *
      * Effect row is Async & Abort[TastyError] (Scope is consumed internally by withClasspath).
      */
    def withKyoCoreClasspath(using Frame): Tasty.Classpath < (Async & Abort[TastyError]) =
        TestClasspaths2Platform.withKyoCoreClasspath

    /** Count "in pending" occurrences in all Fidelity2Test.scala files under shared/src/test (JVM only). */
    def pendingLeafCount: Int = TestClasspaths2Platform.pendingLeafCount

    /** Perform two independent cold loads and return the resulting snapshot byte arrays (JVM only). */
    def twoColdInits(
        roots: Seq[String] = TestClasspaths2Platform.standardRoots
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.twoColdInits(roots)

    /** Locate the worktree root directory (directory containing build.sbt) (JVM only).
      *
      * Walks up from user.dir until build.sbt is found. Used by filesystem-scan leaves. Callers must gate invocations with the `jvmOnly`
      * tag.
      */
    def findWorktreeRoot: String = TestClasspaths2Platform.findWorktreeRoot

    /** Run the concurrent reader+writer snapshot test (JVM only).
      *
      * Writes an initial snapshot, starts a reader fiber via StutterFileSource, starts a writer fiber, releases the stutter latch
      * returns true if the reader completed without a Panic. Callers must gate invocations with the `jvmOnly` tag.
      */
    def runConcurrentReaderWriterTest(
        cp: Tasty.Classpath,
        digest: Array[Byte],
        tmpDir: String
    )(using Frame): Boolean < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.runConcurrentReaderWriterTest(cp, digest, tmpDir)

    /** Load the embedded-fixture classpath with a warning sink (cross-platform).
      *
      * Mirrors `loadStandardWithSink` but uses `TestClasspaths.withClasspath` (embedded fixtures on all platforms,
      * including JVM) instead of the real stdlib classpath. Captures warn messages emitted during decode so callers
      * can assert on unknown-tag counts. Works on JVM, JS, and Native.
      */
    def loadEmbeddedWithSink(using Frame): (Tasty.Classpath, WarningSink) < (Sync & Async & Abort[TastyError]) =
        import AllowUnsafe.embrace.danger
        val bufRef = AtomicRef.Unsafe.init(Chunk.empty[String])
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
                val m = msg; discard(bufRef.updateAndGet(_ :+ m))
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                val m = msg; discard(bufRef.updateAndGet(_ :+ m))
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sinkLogger
        Log.let(Log(sinkLogger)) {
            TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
                (cp, WarningSink(bufRef.get().toSeq))
        }
    end loadEmbeddedWithSink

    /** Perform a cold load then write a snapshot to a MemoryFileSource and read it back, returning (cold, warm).
      *
      * Cross-platform: uses `SnapshotWriter.serializeToBytes` and `SnapshotReader.readBytes` via a MemoryFileSource. Works on JVM, JS
      * Native. On JVM the cold load uses the embedded fixture set from `TestClasspaths.withClasspath` (same as JS/Native). This helper is
      * suitable for testing snapshot round-trip correctness on any platform; for tests requiring the full real stdlib classpath use
      * `TestClasspaths2.standardWithSnapshot` (JVM only).
      */
    def withSnapshotInMemory()(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Sync & Async & Abort[TastyError]) =
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: coldCp =>
            Sync.defer:
                val digest       = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
                val snapshotPath = "mem-snapshot/snapshot.krfl"
                val mem          = MemoryFileSource()
                val bytes        = SnapshotWriter.serializeToBytes(coldCp, digest)
                mem.add(snapshotPath, bytes)
                (mem, snapshotPath, coldCp)
            .flatMap: (mem, snapshotPath, coldCp) =>
                SnapshotReader.read(snapshotPath, mem).map: warmCp =>
                    (coldCp, warmCp)

    /** Create a temporary directory and return its absolute path (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def createTempDir(prefix: String): String = TestClasspaths2Platform.createTempDir(prefix)

    /** Write bytes to a file at the given absolute path (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def writeBytes(path: String, bytes: Array[Byte]): Unit = TestClasspaths2Platform.writeBytes(path, bytes)

    /** List all file names in a directory that end with the given suffix (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def listFilesWithSuffix(dir: String, suffix: String): Array[String] =
        TestClasspaths2Platform.listFilesWithSuffix(dir, suffix)

    /** Walk a directory recursively and return absolute paths of files ending with the given suffix (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def walkFilesWithSuffix(dir: String, suffix: String): Array[String] =
        TestClasspaths2Platform.walkFilesWithSuffix(dir, suffix)

    /** Read a file from an absolute path as a UTF-8 string (JVM only).
      *
      * Callers must gate invocations with the `jvmOnly` tag.
      */
    def readFileAsString(path: String): String = TestClasspaths2Platform.readFileAsString(path)

    /** Read a classpath resource by name as a UTF-8 string (JVM only).
      *
      * Uses the classloader resource stream. Callers must gate invocations with the `jvmOnly` tag.
      */
    def readClasspathResource(resourcePath: String): String =
        TestClasspaths2Platform.readClasspathResource(resourcePath)

end TestClasspaths2
