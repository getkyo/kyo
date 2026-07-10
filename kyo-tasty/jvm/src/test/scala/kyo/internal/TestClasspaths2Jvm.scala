package kyo.internal

import java.io.File
import java.nio.file.Paths
import kyo.*
import kyo.internal.tasty.query.ClasspathOrchestrator

/** JVM-only classpath fixtures for kyo-tasty decoder fidelity tests.
  *
  * Contains the full JVM implementation of the decoder fidelity test infrastructure: real-classpath loading, warning-capture sink, snapshot
  * round-trip helpers, platform-module loading, and synthetic fixture construction. All methods rely on JVM filesystem and JVM-specific APIs
  * (java.nio.file, jrt:/).
  *
  * This object is the backing implementation for `TestClasspaths2Platform` (JVM version), which is referenced by the shared
  * `TestClasspaths2` facade. Shared tests never call this object directly; they go through the facade.
  */
private[kyo] object TestClasspaths2Jvm:

    /** The standard 3-root combo: kyo-tasty + kyo-data + scala-library (same as TestClasspaths.standard). */
    def standardRoots: Seq[String] = TestClasspaths.standard

    /** Execute f with a warning-capturing Log installed for the current fiber.
      *
      * The returned WarningSink collects every warn call that occurs during f's execution. This is fiber-local via Log.let so parallel
      * test fibers do not see each other's log output.
      */
    def withWarningSink[A, S](f: TestClasspaths2.WarningSink => A < S)(using Frame): A < S =
        import AllowUnsafe.embrace.danger
        val bufRef = AtomicRef.Unsafe.init(Chunk.empty[String])
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def name: String                                                           = "kyo.tasty.test"
            def withName(n: String): Log.Unsafe                                        = this
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
            f(TestClasspaths2.WarningSink(bufRef.get().toSeq))
        }
    end withWarningSink

    /** Load the standard classpath with a warning sink. */
    def loadStandardWithSink(using Frame): (Tasty.Classpath, TestClasspaths2.WarningSink) < (Async & Scope & Abort[TastyError]) =
        import AllowUnsafe.embrace.danger
        val bufRef = AtomicRef.Unsafe.init(Chunk.empty[String])
        val sinkLogger: Log.Unsafe = new Log.Unsafe:
            def level: Log.Level                                                       = Log.Level.warn
            def name: String                                                           = "kyo.tasty.test"
            def withName(n: String): Log.Unsafe                                        = this
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
            TestClasspaths.withClasspath(standardRoots)(Tasty.classpath).map { classpath =>
                (classpath, TestClasspaths2.WarningSink(bufRef.get().toSeq))
            }
        }
    end loadStandardWithSink

    /** Perform a cold load then write a snapshot to a temp dir and read it back, returning (cold, warm). */
    def standardWithSnapshot(
        roots: Seq[String] = standardRoots
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Async & Scope & Abort[TastyError]) =
        TestClasspaths.withClasspath(roots)(Tasty.classpath).map { coldClasspath =>
            Sync.defer {
                java.nio.file.Files.createTempDirectory("kyo-snapshot").toString
            }.map { tmpDir =>
                val digest = Array[Byte](0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27)
                kyo.internal.tasty.snapshot.SnapshotWriter.write(coldClasspath, tmpDir, digest).map { _ =>
                    val hexDigest    = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
                    val snapshotPath = s"$tmpDir/$hexDigest.krfl"
                    kyo.internal.tasty.snapshot.SnapshotReader.read(snapshotPath).map { warmClasspath =>
                        (coldClasspath, warmClasspath)
                    }
                }
            }
        }

    /** Load the standard classpath plus java.base JDK classfiles. */
    private val platformCpFuture: java.util.concurrent.Future[Either[Throwable, Tasty.Classpath]] =
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit(new java.util.concurrent.Callable[Either[Throwable, Tasty.Classpath]]:
            def call(): Either[Throwable, Tasty.Classpath] =
                try
                    import AllowUnsafe.embrace.danger
                    given Frame = Frame.internal
                    KyoApp.Unsafe.runAndBlock(Duration.Infinity)(
                        Tasty.Classpath.initWithPlatformModulesFiltered(standardRoots, Set("java.base"))
                    ) match
                        case Result.Success(classpath) => Right(classpath)
                        case Result.Failure(t)         => Left(t)
                        case Result.Panic(t)           => Left(t)
                    end match
                catch
                    case t: Throwable => Left(t))
        executor.shutdown()
        future
    end platformCpFuture

    def standardWithPlatformModules(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        Sync.defer(platformCpFuture.get()).map { either =>
            either match
                case Right(classpath) => classpath
                case Left(t) =>
                    Abort.fail(TastyError.ClassfileFormatError(
                        "<platform-modules-cache>",
                        t.getMessage,
                        0L
                    ))
        }
    end standardWithPlatformModules

    def collisionRoots: Seq[String] = TestClasspaths.kyoTasty ++ TestClasspaths.kyoTasty

    def withCollisionClasspath(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        val concurrency = java.lang.Runtime.getRuntime.availableProcessors().max(1)
        ClasspathOrchestrator.init(collisionRoots, Tasty.ErrorMode.SoftFail, concurrency)
    end withCollisionClasspath

    def withCollisionClasspathFailFast(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        val concurrency = java.lang.Runtime.getRuntime.availableProcessors().max(1)
        ClasspathOrchestrator.init(collisionRoots, Tasty.ErrorMode.FailFast, concurrency)
    end withCollisionClasspathFailFast

    private def writeTempFile(name: String, bytes: Array[Byte]): String =
        val dir  = java.nio.file.Files.createTempDirectory("kyo-fixture")
        val path = dir.resolve(name)
        java.nio.file.Files.write(path, bytes)
        path.toString
    end writeTempFile

    def truncatedTastyPath: String =
        val magic        = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val version      = Array[Byte](0x9c.toByte, 0x88.toByte, 0x80.toByte)
        val toolingLen   = Array[Byte](0x80.toByte)
        val uuid         = Array.fill[Byte](16)(0)
        val nameTableLen = Array[Byte]((100 | 0x80).toByte)
        val nameData     = Array[Byte](1.toByte, 0x83.toByte, 65.toByte, 66.toByte, 67.toByte)
        val bytes        = magic ++ version ++ toolingLen ++ uuid ++ nameTableLen ++ nameData
        writeTempFile("Truncated.tasty", bytes)
    end truncatedTastyPath

    def bitFlippedMagicTastyPath: String =
        val magic   = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val corrupt = Array[Byte]((magic(0) ^ 0x01).toByte) ++ magic.drop(1)
        val version = Array[Byte](28, 5, 0)
        val uuid    = Array.fill[Byte](16)(0)
        val bytes   = corrupt ++ version ++ uuid
        writeTempFile("BitFlipped.tasty", bytes)
    end bitFlippedMagicTastyPath

    def corruptedMidStreamTastyPath: String =
        val magic        = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
        val version      = Array[Byte](0x9c.toByte, 0x88.toByte, 0x80.toByte)
        val toolingLen   = Array[Byte](0x80.toByte)
        val uuid         = Array.fill[Byte](16)(0)
        val nameTableLen = Array[Byte]((50 | 0x80).toByte)
        val nameData     = Array[Byte](1.toByte, 0x82.toByte, 65.toByte)
        val bytes        = magic ++ version ++ toolingLen ++ uuid ++ nameTableLen ++ nameData
        writeTempFile("MidStream.tasty", bytes)
    end corruptedMidStreamTastyPath

    def javaOnlyClassDir: String =
        val dir    = java.nio.file.Files.createTempDirectory("kyo-java-only")
        val pkgDir = dir.resolve("test")
        java.nio.file.Files.createDirectories(pkgDir)
        val classFile             = pkgDir.resolve("JavaOnlyClass.class")
        val out                   = new java.io.ByteArrayOutputStream()
        def writeU1(v: Int): Unit = out.write(v & 0xff)
        def writeU2(v: Int): Unit =
            writeU1(v >> 8); writeU1(v)
        def writeU4(v: Int): Unit =
            writeU2(v >> 16); writeU2(v)
        writeU4(0xcafebabe)
        writeU2(0)
        writeU2(55)
        writeU2(7)
        val nameBytes = "test/JavaOnlyClass".getBytes("UTF-8")
        writeU1(1); writeU2(nameBytes.length); out.write(nameBytes)
        val objBytes = "java/lang/Object".getBytes("UTF-8")
        writeU1(1); writeU2(objBytes.length); out.write(objBytes)
        val initBytes = "<init>".getBytes("UTF-8")
        writeU1(1); writeU2(initBytes.length); out.write(initBytes)
        val descBytes = "()V".getBytes("UTF-8")
        writeU1(1); writeU2(descBytes.length); out.write(descBytes)
        writeU1(7); writeU2(1)
        writeU1(7); writeU2(2)
        writeU2(0x0021)
        writeU2(5)
        writeU2(6)
        writeU2(0); writeU2(0); writeU2(0); writeU2(0)
        java.nio.file.Files.write(classFile, out.toByteArray)
        dir.toString
    end javaOnlyClassDir

    def apOutputClassDir: String =
        val dir    = java.nio.file.Files.createTempDirectory("kyo-ap-output")
        val pkgDir = dir.resolve("test")
        java.nio.file.Files.createDirectories(pkgDir)
        val classFile             = pkgDir.resolve("ApGenerated.class")
        val out                   = new java.io.ByteArrayOutputStream()
        def writeU1(v: Int): Unit = out.write(v & 0xff)
        def writeU2(v: Int): Unit =
            writeU1(v >> 8); writeU1(v)
        def writeU4(v: Int): Unit =
            writeU2(v >> 16); writeU2(v)
        writeU4(0xcafebabe)
        writeU2(0)
        writeU2(55)
        writeU2(5)
        val nameBytes = "test/ApGenerated".getBytes("UTF-8")
        writeU1(1); writeU2(nameBytes.length); out.write(nameBytes)
        val objBytes = "java/lang/Object".getBytes("UTF-8")
        writeU1(1); writeU2(objBytes.length); out.write(objBytes)
        writeU1(7); writeU2(1)
        writeU1(7); writeU2(2)
        writeU2(0x0021)
        writeU2(3)
        writeU2(4)
        writeU2(0); writeU2(0); writeU2(0); writeU2(0)
        java.nio.file.Files.write(classFile, out.toByteArray)
        dir.toString
    end apOutputClassDir

    def multiVersionStdlibRoots: Seq[String] = collisionRoots

    def v3FormatKrflBytes: Array[Byte] =
        val out = new java.io.ByteArrayOutputStream()
        out.write('K'); out.write('R'); out.write('F'); out.write('L')
        out.write(1); out.write(3); out.write(0); out.write(0)
        for _ <- 1 to 8 do out.write(0)
        for _ <- 1 to 8 do out.write(0)
        for _ <- 1 to 8 do out.write(0)
        out.write(0); out.write(0); out.write(0); out.write(0)
        out.toByteArray
    end v3FormatKrflBytes

    def twoColdInits(
        roots: Seq[String] = standardRoots
    )(using Frame): (Array[Byte], Array[Byte]) < (Async & Scope & Abort[TastyError]) =
        val digest = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
        kyo.internal.tasty.query.ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, 1).map { cp1 =>
            Sync.defer {
                java.nio.file.Files.createTempDirectory("kyo-snap-a").toString
            }.map { tmpA =>
                kyo.internal.tasty.snapshot.SnapshotWriter.write(cp1, tmpA, digest).map { _ =>
                    val hexA  = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
                    val pathA = s"$tmpA/$hexA.krfl"
                    kyo.internal.tasty.query.ClasspathOrchestrator.init(roots, Tasty.ErrorMode.SoftFail, 1).map { cp2 =>
                        Sync.defer {
                            java.nio.file.Files.createTempDirectory("kyo-snap-b").toString
                        }.map { tmpB =>
                            val digest2 = Array[Byte](0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37)
                            kyo.internal.tasty.snapshot.SnapshotWriter.write(cp2, tmpB, digest2).map { _ =>
                                val hexB  = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest2)
                                val pathB = s"$tmpB/$hexB.krfl"
                                Abort.recover[kyo.FileException](e => Abort.fail(TastyError.SnapshotIoError(e.getMessage)))(
                                    Path.runReadOnly(Path(pathA).readBytes).map { spanA =>
                                        Abort.recover[kyo.FileException](e => Abort.fail(TastyError.SnapshotIoError(e.getMessage)))(
                                            Path.runReadOnly(Path(pathB).readBytes).map { spanB =>
                                                (spanA.toArray, spanB.toArray)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    end twoColdInits

end TestClasspaths2Jvm
