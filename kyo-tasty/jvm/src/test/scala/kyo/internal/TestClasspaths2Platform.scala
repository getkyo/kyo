package kyo.internal

import kyo.*

/** JVM concrete implementation of the cross-platform TestClasspaths2 facade.
  *
  * Delegates every method to `TestClasspaths2Jvm`, which contains the full JVM-specific real-classpath loading, snapshot round-trip,
  * platform-module loading, and synthetic fixture construction logic.
  *
  * This object is referenced by the shared `TestClasspaths2` facade. On JS and Native, a stub version of this object is provided that throws
  * UnsupportedOperationException for every method. All call sites in shared tests that invoke JVM-only operations are guarded by the
  * `jvmOnly` tag.
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object TestClasspaths2Platform:

    def standardRoots: Seq[String] = TestClasspaths2Jvm.standardRoots

    def loadStandardWithSink(using Frame): (Tasty.Classpath, TestClasspaths2.WarningSink) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.loadStandardWithSink

    def standardWithSnapshot(
        roots: Seq[String] = standardRoots
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.standardWithSnapshot(roots)

    def standardWithPlatformModules(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.standardWithPlatformModules

    def withCollisionClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.withCollisionClasspath

    def withCollisionClasspathFailFast(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.withCollisionClasspathFailFast

    def truncatedTastyPath: String = TestClasspaths2Jvm.truncatedTastyPath

    def bitFlippedMagicTastyPath: String = TestClasspaths2Jvm.bitFlippedMagicTastyPath

    def corruptedMidStreamTastyPath: String = TestClasspaths2Jvm.corruptedMidStreamTastyPath

    def javaOnlyClassDir: String = TestClasspaths2Jvm.javaOnlyClassDir

    def apOutputClassDir: String = TestClasspaths2Jvm.apOutputClassDir

    def multiVersionStdlibRoots: Seq[String] = TestClasspaths2Jvm.multiVersionStdlibRoots

    def v3FormatKrflBytes: Array[Byte] = TestClasspaths2Jvm.v3FormatKrflBytes

    def twoColdInits(
        roots: Seq[String] = standardRoots
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths2Jvm.twoColdInits(roots)

    /** Load a classpath that includes kyo-core (for ContextFunction coverage). JVM only. */
    def withKyoCoreClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        TestClasspaths.withClasspath(TestClasspaths.standardWithKyoCore)

    /** Count "in pending" occurrences in all *Fidelity2Test.scala files under shared/src/test. JVM only. */
    def pendingLeafCount: Int =
        var candidate = java.nio.file.Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while candidate != null && !java.nio.file.Files.exists(candidate.resolve("build.sbt")) do
            candidate = candidate.getParent
        val testDir = candidate.resolve("kyo-tasty/shared/src/test/scala/kyo")
        val allTestFiles =
            java.nio.file.Files
                .walk(testDir)
                .filter(p => p.getFileName.toString.endsWith("Fidelity2Test.scala"))
                .toArray
                .map(_.asInstanceOf[java.nio.file.Path])
        var count = 0
        allTestFiles.foreach: p =>
            val src = new String(java.nio.file.Files.readAllBytes(p), "UTF-8")
            var idx = 0
            while idx < src.length do
                val found = src.indexOf("in pending", idx)
                if found == -1 then idx = src.length
                else
                    count += 1
                    idx = found + 1
                end if
            end while
        count
    end pendingLeafCount

end TestClasspaths2Platform
