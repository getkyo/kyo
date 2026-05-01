package kyo.test

import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import zio.test.*

object ScopeAssertEvalSpec extends KyoSpecDefault:

    private def tempDir(using Frame): Path < (Scope & Sync) =
        Scope.acquireRelease(
            Sync.defer(Files.createTempDirectory("scope-assert-eval-").nn)
        )(p => Sync.defer(deleteRecursive(p)))

    private def deleteRecursive(path: Path): Unit =
        if Files.isDirectory(path) then
            val entries = Files.list(path).nn
            try entries.forEach(p => deleteRecursive(p.nn))
            finally entries.close()
        end if
        Files.deleteIfExists(path)
        ()
    end deleteRecursive

    private def writeMarker(dir: Path): Path =
        val file = dir.resolve("marker.txt").nn
        Files.writeString(file, "hi")
        file
    end writeMarker

    private def fileExists(p: Path): Boolean = Files.exists(p)

    def spec = suite("ScopeAssertEvalSpec")(
        test("assertTrue captures inside Scope see live resources"):
            for
                dir <- tempDir
                file = writeMarker(dir)
            yield assertTrue(fileExists(file))
        ,
        test("eagerly captured Boolean works"):
            for
                dir <- tempDir
                file   = writeMarker(dir)
                exists = fileExists(file)
            yield assertTrue(exists)
        ,
        test("Sync.defer right before yield sees the file"):
            for
                dir <- tempDir
                file = writeMarker(dir)
                seen <- Sync.defer(fileExists(file))
            yield assertTrue(seen)
    )
end ScopeAssertEvalSpec
