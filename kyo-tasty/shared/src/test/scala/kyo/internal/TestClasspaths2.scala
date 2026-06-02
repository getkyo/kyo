package kyo.internal

import kyo.*

/** Cross-platform facade for decoder-fidelity-2 test infrastructure.
  *
  * On JVM this delegates to `TestClasspaths2Platform` (provided by jvm/src/test) which wraps the real-classpath JVM implementation. On JS
  * and Native it delegates to platform-specific stubs that throw `UnsupportedOperationException` for JVM-only operations. All call sites
  * that invoke JVM-only methods are guarded by the `jvmOnly` tag in `Fidelity2TestBase.coldWarmEquiv` or by explicit `- jvmOnly` in the
  * test leaf, so the stubs are never reached at runtime on JS/Native.
  *
  * The surface exposed here is the minimal subset required by the relocated Fidelity2 test files. Methods not used by shared tests are not
  * exposed.
  *
  * Scaladoc: 8-35 lines.
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

    /** Load a classpath that includes kyo-core for ContextFunction coverage (JVM only). */
    def withKyoCoreClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.withKyoCoreClasspath

    /** Count "in pending" occurrences in all Fidelity2Test.scala files under shared/src/test (JVM only). */
    def pendingLeafCount: Int = TestClasspaths2Platform.pendingLeafCount

    /** Perform two independent cold loads and return the resulting snapshot byte arrays (JVM only). */
    def twoColdInits(
        roots: Seq[String] = TestClasspaths2Platform.standardRoots
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Platform.twoColdInits(roots)

end TestClasspaths2
