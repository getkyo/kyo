package kyo.stats.machine

import kyo.*

class MachineStatFactoryJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "a started sampler" - {

        "is the fiber triggerStart answers, and once it is interrupted and the CAS reset a later start wins again".onlyJvm in {
            MachineStatFactory.resetForTest()
            val started = MachineStatFactory.triggerStart(disabled = false)
            assert(started.isDefined)
            started.foreach(fiber => discard(fiber.interrupt()))
            MachineStatFactory.resetForTest()
            assert(!MachineStatFactory.hasStarted)
            val startedAgain = MachineStatFactory.triggerStart(disabled = false)
            assert(startedAgain.isDefined)
            startedAgain.foreach(fiber => discard(fiber.interrupt()))
            MachineStatFactory.resetForTest()
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
        val args      = List(javaBin, "--enable-native-access=ALL-UNNAMED") ++
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
