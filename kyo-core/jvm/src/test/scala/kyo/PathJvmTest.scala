package kyo

import java.nio.file.Files as JFiles

// JVM-only Path tests that require java.nio.file features not available on
// Scala.js: toJava, Path.of(java.nio.file.Path), and symlink creation.
class PathJvmTest extends Test:

    // =========================================================================
    // toJava / Path.of
    // =========================================================================

    "toJava returns java.nio.file.Path and its string matches toString" in {
        val p                         = Path / "usr" / "local" / "bin"
        val javaP: java.nio.file.Path = p.toJava
        assert(javaP.toString == p.toString)
    }

    "Path.of(javaPath) round-trips with toJava" in {
        val jpath = java.nio.file.Path.of("/usr/local/bin")
        assert(Path.of(jpath).toJava.equals(jpath.normalize()))
    }

    "Path.of normalizes the path" in {
        val jpath   = java.nio.file.Path.of("/usr/./local/../bin")
        val kyoPath = Path.of(jpath)
        assert(kyoPath.toString == "/usr/bin")
    }

    "Path.of on relative java path" in {
        val jpath   = java.nio.file.Path.of("a/b")
        val kyoPath = Path.of(jpath)
        assert(kyoPath.isAbsolute == false)
    }

    // =========================================================================
    // Symlink tests (require JFiles.createSymbolicLink)
    // =========================================================================

    "isSymbolicLink returns true for a symbolic link" in run {
        val tmp    = JFiles.createTempDirectory("kyo-test")
        val target = tmp.resolve("target.txt")
        val link   = tmp.resolve("link.txt")
        JFiles.createFile(target)
        JFiles.createSymbolicLink(link, target)
        val p = Path(link.toString)
        for
            result <- p.isSymbolicLink
            _      <- (Path(tmp.toString)).removeAll
        yield assert(result)
        end for
    }

    "exists(followLinks=false) on symlink returns true; exists(true) returns false for dangling link" in run {
        val tmp    = JFiles.createTempDirectory("kyo-test")
        val target = tmp.resolve("ghost-target.txt")
        val link   = tmp.resolve("dangling-link.txt")
        JFiles.createSymbolicLink(link, target)
        val p = Path(link.toString)
        for
            existsNoFollow <- p.exists(followLinks = false)
            existsFollow   <- p.exists(followLinks = true)
            _              <- Path(tmp.toString).removeAll
        yield assert(existsNoFollow && !existsFollow)
        end for
    }

    "walk with followLinks=false does not follow symlinks" in run {
        val tmp    = JFiles.createTempDirectory("kyo-path-dir-test")
        val target = JFiles.createTempDirectory("kyo-path-dir-test-target")
        val inner  = target.resolve("inner.txt")
        JFiles.createFile(inner)
        val link = tmp.resolve("link-to-target")
        JFiles.createSymbolicLink(link, target)
        val dir = Path(tmp.toString)
        for
            paths <- Scope.run(dir.walk(followLinks = false).run)
            _     <- Path(tmp.toString).removeAll
            _     <- Path(target.toString).removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("link-to-target") && !names.contains("inner.txt"))
        end for
    }

    "walk with followLinks=false terminates on a symlink cycle" in run {
        val tmp      = JFiles.createTempDirectory("kyo-path-edge-test")
        val dir      = Path(tmp.toString)
        val linkPath = tmp.resolve("cycle-link")
        JFiles.createSymbolicLink(linkPath, tmp)
        for
            result <- Abort.run[FileFsException](Scope.run(dir.walk(followLinks = false).run))
            _      <- dir.removeAll
        yield result match
            case Result.Success(paths) =>
                val names = paths.toList.map(_.parts.last)
                assert(names.contains("cycle-link"))
                succeed
            case Result.Failure(e) =>
                fail(s"Expected walk to succeed without error, got failure: $e")
        end for
    }

    "walk(followLinks=true) follows symlinks into target directory" in run {
        val dirA  = JFiles.createTempDirectory("kyo-path-dir-test-a")
        val dirB  = JFiles.createTempDirectory("kyo-path-dir-test-b")
        val inner = dirA.resolve("inner.txt")
        JFiles.createFile(inner)
        val link = dirB.resolve("link-to-a")
        JFiles.createSymbolicLink(link, dirA)
        val walkRoot = Path(dirB.toString)
        for
            paths <- Scope.run(walkRoot.walk(maxDepth = Int.MaxValue, followLinks = true).run)
            _     <- Path(dirA.toString).removeAll
            _     <- Path(dirB.toString).removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("inner.txt"))
        end for
    }

    "copy with followLinks=false copies symlink as symlink" in run {
        val tmp    = JFiles.createTempDirectory("kyo-path-dir-test")
        val target = tmp.resolve("target.txt")
        JFiles.createFile(target)
        val link = tmp.resolve("link.txt")
        JFiles.createSymbolicLink(link, target)
        val src = Path(link.toString)
        val dst = Path(tmp.resolve("copy-of-link.txt").toString)
        for
            _              <- src.copy(dst, followLinks = false)
            isSymbolicLink <- dst.isSymbolicLink
            _              <- Path(tmp.toString).removeAll
        yield assert(isSymbolicLink)
        end for
    }

end PathJvmTest
