package kyo.doctest.internal

import kyo.*

object RuntimeExecutorTimeoutTarget:
    val _ = java.nio.file.Files.writeString(
        java.nio.file.Path.of(java.lang.System.getProperty("kyo.doctest.progress")),
        "7"
    )
    while true do java.lang.Thread.onSpinWait()
end RuntimeExecutorTimeoutTarget

object RuntimeExecutorExitTarget:
    val _ = java.nio.file.Files.writeString(
        java.nio.file.Path.of(java.lang.System.getProperty("kyo.doctest.progress")),
        "42"
    )
    java.lang.System.exit(23)
end RuntimeExecutorExitTarget

object RuntimeExecutorPerBlockTimeoutTarget:
    val progress = java.nio.file.Path.of(java.lang.System.getProperty("kyo.doctest.progress"))
    val _        = java.nio.file.Files.writeString(progress, "11")
    val until    = java.lang.System.nanoTime() + 120_000_000L
    while java.lang.System.nanoTime() < until do java.lang.Thread.onSpinWait()
    val _ = java.nio.file.Files.writeString(progress, "22")
    while true do java.lang.Thread.onSpinWait()
end RuntimeExecutorPerBlockTimeoutTarget

class RuntimeExecutorTest extends kyo.test.Test[Any]:

    private def testClasspath(using Frame): Chunk[kyo.Path] < Sync =
        for
            cp  <- System.property[String]("java.class.path", "")
            sep <- System.property[String]("path.separator", ":")
        yield Chunk.from(cp.split(sep).filter(_.nonEmpty).map(kyo.Path(_)))

    private def withOutputDir[A, S](f: kyo.Path => A < (Sync & Async & S))(using Frame): A < (Sync & Async & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"runtime-executor-test-$id"
            _      <- Abort.run[FileStructureException](dir.mkDir).unit
            result <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileStructureException](dir.removeAll).unit).flatMap(f)
        yield result

    "times out and terminates a non-cooperative block process" in {
        withOutputDir { outputDir =>
            for
                cp <- testClasspath
                result <- RuntimeExecutor.execute(
                    "kyo.doctest.internal.RuntimeExecutorTimeoutTarget$",
                    kyo.Path("RuntimeExecutorTest.scala"),
                    outputDir,
                    cp,
                    Map(7 -> 250.millis),
                    5.seconds
                )
            yield result match
                case RuntimeExecutor.Outcome.TimedOut(after, RuntimeExecutor.Progress.Block(7)) =>
                    assert(after == 250.millis)
                case other => fail(s"expected timeout in block 7, got $other")
        }
    }

    "contains System.exit inside the block process" in {
        withOutputDir { outputDir =>
            for
                cp <- testClasspath
                result <- RuntimeExecutor.execute(
                    "kyo.doctest.internal.RuntimeExecutorExitTarget$",
                    kyo.Path("RuntimeExecutorTest.scala"),
                    outputDir,
                    cp,
                    5.seconds
                )
            yield result match
                case RuntimeExecutor.Outcome.ProcessExited(23, _, RuntimeExecutor.Progress.Block(42)) =>
                    succeed("captured child exit")
                case other => fail(s"expected exit code 23 in block 42, got $other")
        }
    }

    "resets the deadline and applies the active block timeout" in {
        withOutputDir { outputDir =>
            for
                cp <- testClasspath
                result <- RuntimeExecutor.execute(
                    "kyo.doctest.internal.RuntimeExecutorPerBlockTimeoutTarget$",
                    kyo.Path("RuntimeExecutorTest.scala"),
                    outputDir,
                    cp,
                    Map(11 -> 500.millis, 22 -> 75.millis),
                    5.seconds
                )
            yield result match
                case RuntimeExecutor.Outcome.TimedOut(after, RuntimeExecutor.Progress.Block(22)) =>
                    assert(after == 75.millis)
                case other => fail(s"expected block 22 to time out after 75 milliseconds, got $other")
        }
    }

    "distinguishes child bootstrap failure from block execution" in {
        withOutputDir { outputDir =>
            RuntimeExecutor.execute(
                "missing.Target$",
                kyo.Path("Missing.scala"),
                outputDir,
                Chunk.empty,
                5.seconds
            ).map {
                case RuntimeExecutor.Outcome.ProcessExited(_, _, RuntimeExecutor.Progress.NotStarted) =>
                    succeed("child runner did not start")
                case other => fail(s"expected pre-start process exit, got $other")
            }
        }
    }

end RuntimeExecutorTest
