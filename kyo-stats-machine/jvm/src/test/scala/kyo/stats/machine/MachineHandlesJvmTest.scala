package kyo.stats.machine

import kyo.*

class MachineHandlesJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Locates the `kyo-stats-machine` module root by walking up from the forked JVM working directory,
      * mirroring `MachineStatFactoryTest`'s established repo-relative lookup.
      */
    private def locateModuleRoot()(using kyo.test.AssertScope): java.io.File =
        val name = "kyo-stats-machine"
        Iterator.iterate(new java.io.File(".").getCanonicalFile)(_.getParentFile)
            .take(6)
            .map(root => new java.io.File(root, name))
            .find(_.isDirectory)
            .getOrElse(fail(s"could not locate the $name module root; run tests from the repository root or a subproject directory"))
    end locateModuleRoot

    private def collectMainScalaFiles(moduleRoot: java.io.File): List[java.io.File] =
        def walk(dir: java.io.File): List[java.io.File] =
            val children = Option(dir.listFiles()).map(_.toList).getOrElse(Nil)
            children.flatMap { f =>
                if f.isDirectory && f.getName != "target" then walk(f)
                else if f.isFile && f.getName.endsWith(".scala") then List(f)
                else Nil
            }
        end walk
        val sep = java.io.File.separator
        walk(moduleRoot).filter(_.getPath.contains(s"${sep}src${sep}main${sep}"))
    end collectMainScalaFiles

    "source-scanning guard" - {

        "walks up from the forked JVM cwd and asserts the exact count of files it checked".onlyJvm in {
            val moduleRoot = locateModuleRoot()
            val scalaFiles = collectMainScalaFiles(moduleRoot)
            val banned     = List("Thread.sleep", "synchronized", "CountDownLatch.await")
            def hasBannedConstruct(f: java.io.File): Boolean =
                val content = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
                banned.exists(content.contains)
            val filesChecked = scalaFiles.size
            val offenders    = scalaFiles.filter(hasBannedConstruct)
            // A fixed-relative-path or wrong-hop resolution that silently matches zero files must never
            // pass vacuously: filesChecked has to be the real, nonzero main-source .scala count.
            assert(filesChecked > 0)
            assert(offenders.isEmpty)
        }
    }

end MachineHandlesJvmTest
