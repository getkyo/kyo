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

    "classpath-presence activation, end to end" - {

        "a KyoApp that never names a metric produces machine.* series".onlyJvm in {
            // The acceptance criterion for the blocker, and the one thing the unit halves cannot show: the
            // reported failure was an application built from Clock, Signal, Fiber and a server running for 55
            // seconds and registering nothing, because nothing in kyo-core ever reached kyo.Stat. It has to be
            // a SEPARATE process: activation is once per process and this module's own test JVM deliberately
            // runs with the sampler opted out, so the check cannot be made in-process.
            //
            // MachineStatsDemoApp is a KyoApp whose body touches no metric at all; it waits a few ticks and
            // prints what the registry holds, exiting non-zero if the snapshot is empty or implausible.
            val (code, output) = runDemoApp(disabled = false)
            assert(code == 0, s"the demo app exited $code:\n$output")
            assert(output.contains("validation: OK"), s"the demo app did not validate its own snapshot:\n$output")
            // The series the finding measured as absent: a cpu rate carrying real observations.
            assert(output.contains("machine.cpu.total.rate"), s"no cpu rate in the snapshot:\n$output")
            val observed = output.linesIterator.find(_.contains("machine.cpu.total.rate")).getOrElse("")
            val obsCount = "obs=(\\d+)".r.findFirstMatchIn(observed).map(_.group(1).toLong).getOrElse(0L)
            assert(obsCount > 0, s"machine.cpu.total.rate registered but never advanced: $observed")
            // The packaging half of E3-01 is only worth having if the native it ships actually feeds the
            // families the audit recorded. Asserting the whole set here, rather than trusting the demo's own
            // verdict line, is what makes a PARTIAL regression visible: a build that lost swap, or two of the
            // four cpu rates, still prints "validation: OK" if this leaf only reads that line. The required
            // set is host-dependent (Windows has no load average), so it is taken from the OS the child
            // itself reported rather than from this process.
            val hostOs = output.linesIterator.collectFirst { case l if l.startsWith("host OS: ") => l.stripPrefix("host OS: ").trim }
            assert(hostOs.isDefined, s"the demo app did not report its host OS:\n$output")
            val required = demo.MachineStatsDemo.requiredKeys(hostOs.get).map(_.dotted)
            val absent   = required.filterNot(k => output.linesIterator.exists(_.contains(k)))
            assert(absent.isEmpty, s"families missing from the demo's snapshot: ${absent.mkString(", ")}\n$output")
        }

        "the same app with the sampler opted out produces nothing, so the leaf above is not passing vacuously".onlyJvm in {
            // The control. Without it a green result above could mean the assertion is insensitive rather
            // than that activation works: same binary, same entrypoint, same classpath, one lever flipped.
            val (code, output) = runDemoApp(disabled = true)
            assert(code != 0, s"the opt-out did not suppress the sampler:\n$output")
            assert(output.contains("validation FAILED"), s"expected a failed validation:\n$output")
        }
    }

    /** Forks a JVM on this test run's own classpath running `demo.MachineStatsDemoApp`, a `KyoApp` whose body
      * names no metric, and returns its exit code and combined output. A separate process is required because
      * activation is once per process and this module's test JVM deliberately runs with the sampler opted out.
      */
    private def runDemoApp(disabled: Boolean): (Int, String) =
        val classpath = java.lang.System.getProperty("java.class.path")
        val javaHome  = java.lang.System.getProperty("java.home")
        val javaBin   = new java.io.File(new java.io.File(javaHome, "bin"), "java").getAbsolutePath
        val args = List(javaBin, "--enable-native-access=ALL-UNNAMED") ++
            (if disabled then List("-Dkyo.machine.disabled=true") else Nil) ++
            List("-cp", classpath, "demo.MachineStatsDemoApp")
        val pb = new java.lang.ProcessBuilder(args*)
        pb.redirectErrorStream(true)
        val proc   = pb.start()
        val output = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        (proc.waitFor(), output)
    end runDemoApp

    "auto-start opt-out" - {

        "the module's own test JVM runs with the host sampler opted out".onlyJvm in {
            // Module invariant: the suites must never run with a live sampler. The once-per-second sampler
            // mutates the shared process-global machine.* handles, so if it auto-started during a test it
            // would race the suites' destructive counter-drain reads and make them flaky. The build disables
            // it for this module's Test config (`-Dkyo.machine.disabled=true`); this leaf asserts the forked
            // test JVM actually carries that property, so a build change that dropped it fails loudly here.
            assert(java.lang.System.getProperty("kyo.machine.disabled") == "true")
        }
    }

end MachineStatFactoryJvmTest
