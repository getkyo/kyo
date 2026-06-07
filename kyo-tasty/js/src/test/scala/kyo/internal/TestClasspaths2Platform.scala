package kyo.internal

import kyo.*

/** JS stub for the cross-platform TestClasspaths2 facade.
  *
  * Every method throws UnsupportedOperationException because all JVM-only operations are guarded by the `jvmOnly` tag in the shared test
  * leaves. The stubs are present to satisfy the Scala compiler's need for the `TestClasspaths2Platform` object on every platform.
  */
private[kyo] object TestClasspaths2Platform:

    def standardRoots: Seq[String] = throw new UnsupportedOperationException("JVM-only: standardRoots")

    def loadStandardWithSink(using Frame): (Tasty.Classpath, TestClasspaths2.WarningSink) < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: loadStandardWithSink")

    def standardWithSnapshot(
        roots: Seq[String] = Seq.empty
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: standardWithSnapshot")

    def standardWithPlatformModules(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: standardWithPlatformModules")

    def withCollisionClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: withCollisionClasspath")

    def withCollisionClasspathFailFast(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: withCollisionClasspathFailFast")

    def truncatedTastyPath: String = throw new UnsupportedOperationException("JVM-only: truncatedTastyPath")

    def bitFlippedMagicTastyPath: String = throw new UnsupportedOperationException("JVM-only: bitFlippedMagicTastyPath")

    def corruptedMidStreamTastyPath: String = throw new UnsupportedOperationException("JVM-only: corruptedMidStreamTastyPath")

    def javaOnlyClassDir: String = throw new UnsupportedOperationException("JVM-only: javaOnlyClassDir")

    def apOutputClassDir: String = throw new UnsupportedOperationException("JVM-only: apOutputClassDir")

    def multiVersionStdlibRoots: Seq[String] = throw new UnsupportedOperationException("JVM-only: multiVersionStdlibRoots")

    def v3FormatKrflBytes: Array[Byte] = throw new UnsupportedOperationException("JVM-only: v3FormatKrflBytes")

    def twoColdInits(
        roots: Seq[String] = Seq.empty
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: twoColdInits")

    def withKyoCoreClasspath(using Frame): Tasty.Classpath < (Async & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: withKyoCoreClasspath")

    def pendingLeafCount: Int = throw new UnsupportedOperationException("JVM-only: pendingLeafCount")

    def findWorktreeRoot: String = throw new UnsupportedOperationException("JVM-only: findWorktreeRoot")

    def runConcurrentReaderWriterTest(
        cp: Tasty.Classpath,
        digest: Array[Byte],
        tmpDir: String
    )(using Frame): Boolean < (Async & Scope & Abort[TastyError]) =
        throw new UnsupportedOperationException("JVM-only: runConcurrentReaderWriterTest")

    def createTempDir(prefix: String): String =
        throw new UnsupportedOperationException("JVM-only: createTempDir")

    def writeBytes(path: String, bytes: Array[Byte]): Unit =
        throw new UnsupportedOperationException("JVM-only: writeBytes")

    def listFilesWithSuffix(dir: String, suffix: String): Array[String] =
        throw new UnsupportedOperationException("JVM-only: listFilesWithSuffix")

    def walkFilesWithSuffix(dir: String, suffix: String): Array[String] =
        throw new UnsupportedOperationException("JVM-only: walkFilesWithSuffix")

    def readFileAsString(path: String): String =
        throw new UnsupportedOperationException("JVM-only: readFileAsString")

    def readClasspathResource(resourcePath: String): String =
        throw new UnsupportedOperationException("JVM-only: readClasspathResource")

end TestClasspaths2Platform
