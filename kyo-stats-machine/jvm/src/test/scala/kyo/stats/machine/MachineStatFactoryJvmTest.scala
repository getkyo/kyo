package kyo.stats.machine

import kyo.*

class MachineStatFactoryJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val emptyReader: System.Unsafe =
        new System.Unsafe:
            def env(name: String)(using AllowUnsafe): Maybe[String]      = Absent
            def property(name: String)(using AllowUnsafe): Maybe[String] = Absent
            def lineSeparator()(using AllowUnsafe): String               = "\n"
            def userName()(using AllowUnsafe): String                    = "test"
            def operatingSystem()(using AllowUnsafe): System.OS          = System.OS.Unknown
            def architecture()(using AllowUnsafe): System.Arch           = System.Arch.Unknown
            def availableProcessors()(using AllowUnsafe): Int            = 1

    /** Locates the `kyo-stats-machine` module root by walking up from the JVM working directory, mirroring
      * `NativeCallbackCatalogLockstepTest`'s established repo-relative lookup.
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

    "stopForTest" - {

        "interrupts the last-started sampler fiber and clears the CAS, and no production code calls it".onlyJvm in {
            MachineStatFactory.resetForTest()
            val started = MachineStatFactory.triggerStart(emptyReader)
            assert(started)
            MachineStatFactory.stopForTest()
            assert(!MachineStatFactory.hasStarted)
            val startedAgain = MachineStatFactory.triggerStart(emptyReader)
            assert(startedAgain) // the CAS cleared, so a later triggerStart can win again
            MachineStatFactory.stopForTest()

            val moduleRoot = locateModuleRoot()
            val scalaFiles = collectMainScalaFiles(moduleRoot)
            assert(scalaFiles.nonEmpty)
            def contains(f: java.io.File, token: String): Boolean =
                new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8).contains(token)
            val callSites = scalaFiles.filter(contains(_, ".stopForTest("))
            val defSites  = scalaFiles.filter(contains(_, "def stopForTest("))
            assert(callSites.isEmpty)
            assert(defSites.size == 1)
        }
    }

    "test-run opt-out" - {

        "the module's own forked test JVM has the auto-start opt-out property set".onlyJvm in {
            // The build forces `-Dkyo.machine.disabled=true` into this module's Test-config javaOptions
            // (build.sbt), so every leaf here runs where auto-start is suppressed and the once-per-second
            // sampler cannot race the suites' destructive counter-drain reads on the shared machine.*
            // handles. The demo entry point runs in the run scope, which sheds this opt-out; asserting the
            // property here guards that the test scope keeps it, so neither carve-out leaks into the other.
            assert(java.lang.System.getProperty("kyo.machine.disabled") == "true")
        }
    }

end MachineStatFactoryJvmTest
