package kyo.internal

import java.lang.reflect.Field
import java.util.IdentityHashMap
import kyo.*
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** JVM platform tests for [[BrowserLauncherPlatform]].
  *
  * Three scenarios:
  *   1. Hook installation: register then remove via removeShutdownHook round-trip.
  *   2. Hook executes on JVM exit: real sub-JVM spawn writes sentinel file via the hook.
  *   3. Hook thread is non-daemon: registered thread has isDaemon == false.
  *
  * Shutdown hooks are stored in `java.lang.ApplicationShutdownHooks.hooks` (an IdentityHashMap). The global test JVM opens
  * `java.base/java.lang` via `--add-opens`, so setAccessible(true) is allowed in the test suite.
  */
class BrowserLauncherPlatformTest extends BaseBrowserTest:

    // --- helpers ---

    /** Looks up `<className>.<fieldName>` reflectively and returns the (already setAccessible) Field, or raises
      * [[BrowserLauncherJdkIncompatible]] with a JDK-version-tagged message describing which lookup failed.
      *
      * Used by [[registeredHooks]] to fail fast with a clear diagnostic when a JDK upgrade renames or removes
      * `java.lang.ApplicationShutdownHooks.hooks` (rather than surfacing as an opaque `NoSuchFieldException` / `ClassNotFoundException`
      * many call sites away from the actual test).
      */
    private[kyo] def reflectionSanityCheck(className: String, fieldName: String): Field =
        val cls =
            try Class.forName(className)
            catch
                case e: ClassNotFoundException =>
                    throw BrowserLauncherJdkIncompatible(
                        s"JDK incompatibility: class '$className' not found on this JVM " +
                            s"(java.version=${java.lang.System.getProperty("java.version")}). " +
                            s"BrowserLauncherPlatform's shutdown-hook reflection assumes this class exists.",
                        e
                    )
        val field =
            try cls.getDeclaredField(fieldName)
            catch
                case e: NoSuchFieldException =>
                    throw BrowserLauncherJdkIncompatible(
                        s"JDK incompatibility: field '$fieldName' not found on '$className' " +
                            s"(java.version=${java.lang.System.getProperty("java.version")}). " +
                            s"BrowserLauncherPlatform's shutdown-hook reflection assumes this field exists.",
                        e
                    )
        field.setAccessible(true)
        field
    end reflectionSanityCheck

    /** Retrieves all currently-registered shutdown hook threads via reflection on ApplicationShutdownHooks.
      *
      * Requires --add-opens=java.base/java.lang=ALL-UNNAMED (set globally in build.sbt).
      */
    private def registeredHooks(): Set[Thread] =
        val f = reflectionSanityCheck("java.lang.ApplicationShutdownHooks", "hooks")
        f.get(null) match
            case m: IdentityHashMap[?, ?] => m.keySet().asScala.collect { case t: Thread => t }.toSet
            case _                        => Set.empty
        end match
    end registeredHooks

    /** Find a registered shutdown hook thread by name. */
    private def findHookByName(name: String): Maybe[Thread] =
        Maybe.fromOption(registeredHooks().find(_.getName == name))

    // --- reflection sanity ---

    "reflection sanity check raises typed JDK incompatibility error" in {
        val jdkVersion = java.lang.System.getProperty("java.version")

        val missingClassEx = intercept[BrowserLauncherJdkIncompatible] {
            reflectionSanityCheck("java.lang.NoSuchClassXyzzy", "irrelevant")
        }
        assert(missingClassEx.getMessage.contains("JDK incompatibility"))
        assert(missingClassEx.getMessage.contains("'java.lang.NoSuchClassXyzzy'"))
        assert(missingClassEx.getMessage.contains(jdkVersion))

        val missingFieldEx = intercept[BrowserLauncherJdkIncompatible] {
            reflectionSanityCheck("java.lang.String", "noSuchFieldXyzzy")
        }
        assert(missingFieldEx.getMessage.contains("JDK incompatibility"))
        assert(missingFieldEx.getMessage.contains("'noSuchFieldXyzzy'"))
        assert(missingFieldEx.getMessage.contains(jdkVersion))
    }

    // Hook installation: register then remove; assert removeShutdownHook returns true.
    "registerShutdownHook installs a shutdown hook that can be found and removed" in {
        Scope.run {
            Command("sh", "-c", "sleep 30").spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHook(proc).map { _ =>
                    val thread = findHookByName("kyo-browser-shutdown-killer")
                    thread match
                        case Absent =>
                            fail("kyo-browser-shutdown-killer not found in ApplicationShutdownHooks after registerShutdownHook")
                        case Present(t) =>
                            val removed = Runtime.getRuntime.removeShutdownHook(t)
                            assert(removed, "removeShutdownHook should return true (hook was registered)")
                    end match
                }
            }
        }
    }

    // Hook executes on JVM exit (sub-JVM).
    // The fixture `kyo.internal.ShutdownHookFixture` is spawned as a fresh JVM. It:
    //   1. spawns `sleep 60` via raw java.lang.ProcessBuilder (NOT kyo.Command.spawn → no Scope),
    //   2. writes the inner proc's PID to the sentinel file passed as args(0),
    //   3. calls BrowserLauncherPlatform.registerShutdownHook(proc),
    //   4. calls java.lang.System.exit(0) → JVM shutdown hooks fire → hook kills the inner proc.
    // The parent then probes the inner PID with `kill -0` to confirm the hook actually killed it.
    "hook executes and kills process on JVM exit (sub-JVM scenario)" in {
        val sentinel = java.nio.file.Files.createTempFile("kyo-browser-hook-fixture-", ".pid")
        java.nio.file.Files.delete(sentinel) // fixture writes from scratch
        try
            val javaBin   = java.lang.System.getProperty("java.home") + "/bin/java"
            val classpath = java.lang.System.getProperty("java.class.path")
            val cmd = java.util.List.of(
                javaBin,
                "-cp",
                classpath,
                "kyo.internal.ShutdownHookFixture",
                sentinel.toString
            )
            val pb      = new java.lang.ProcessBuilder(cmd).inheritIO()
            val fixture = pb.start()

            // Bound fixture exit at 30s (generous for cold sbt sub-JVM startup).
            val exited = fixture.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS)
            assert(exited, "ShutdownHookFixture sub-JVM did not exit within 30s")
            assert(
                fixture.exitValue() == 0,
                s"ShutdownHookFixture sub-JVM exited with non-zero code ${fixture.exitValue()}"
            )

            // Sentinel must exist with a non-empty PID written by the fixture.
            assert(
                java.nio.file.Files.exists(sentinel),
                s"sentinel file $sentinel was not written by ShutdownHookFixture"
            )
            val pidText = new String(
                java.nio.file.Files.readAllBytes(sentinel),
                java.nio.charset.StandardCharsets.UTF_8
            ).trim
            assert(pidText.nonEmpty, "sentinel file is empty (fixture did not write inner PID)")
            val pid = pidText.toLong

            // Verify the inner proc was killed by the fixture's shutdown hook.
            // Bounded retry on `kill -0 <pid>` (SIGKILL propagation can take a few ms).
            def isAlive(p: Long): Boolean =
                val probe = new java.lang.ProcessBuilder("kill", "-0", p.toString)
                    .redirectErrorStream(true).start()
                probe.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS)
                probe.exitValue() == 0
            end isAlive

            @tailrec def poll(n: Int): (Boolean, Int) =
                if !isAlive(pid) || n >= 20 then (isAlive(pid), n)
                else
                    java.lang.Thread.sleep(50L) // bounded retry, total cap = 1s
                    poll(n + 1)
            val (alive, attempts) = poll(0)
            assert(
                !alive,
                s"inner sleep proc pid=$pid was NOT killed by the fixture's shutdown hook " +
                    s"(still alive after ${attempts * 50}ms of polling)"
            )
        finally
            try
                discard(java.nio.file.Files.deleteIfExists(sentinel))
            catch case _: Throwable => ()
            end try
        end try
    }

    // Hook thread is non-daemon.
    "registerShutdownHook installs a non-daemon thread" in {
        Scope.run {
            Command("sh", "-c", "sleep 30").spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHook(proc).map { _ =>
                    val thread = findHookByName("kyo-browser-shutdown-killer")
                    thread match
                        case Absent =>
                            fail("kyo-browser-shutdown-killer not found in ApplicationShutdownHooks after registerShutdownHook")
                        case Present(t) =>
                            val isDaemon = t.isDaemon
                            // Always clean up the hook to avoid accumulating zombie hooks.
                            Runtime.getRuntime.removeShutdownHook(t)
                            assert(!isDaemon, s"shutdown hook thread must not be a daemon thread, but isDaemon=$isDaemon")
                    end match
                }
            }
        }
    }

end BrowserLauncherPlatformTest

/** Test-scoped exception raised by [[BrowserLauncherPlatformTest.reflectionSanityCheck]] when the JDK class or field that
  * `BrowserLauncherPlatform`'s shutdown-hook reflection depends on is unavailable. The message embeds the active JDK version so a future
  * regression presents as a typed test-infra failure instead of an opaque `NoSuchFieldException` / `ClassNotFoundException`.
  */
final private[kyo] class BrowserLauncherJdkIncompatible(message: String, cause: Throwable)
    extends RuntimeException(message, cause)

/** Sub-JVM fixture for the "hook executes and kills process on JVM exit" scenario.
  *
  * Spawned as a fresh JVM via:
  * {{{
  *   java -cp <classpath> kyo.internal.ShutdownHookFixture <sentinel-file>
  * }}}
  *
  * Lives at top-level inside this test file (same package, same compilation unit) so the Frame macro can derive call-site frames for the
  * kyo effects in the body without resorting to Frame.internal. The compiled class name is still `kyo.internal.ShutdownHookFixture`,
  * which is what the test's ProcessBuilder invokes.
  *
  * Steps the fixture performs:
  *   1. Spawns a long-running `sleep 60` child via raw `java.lang.ProcessBuilder` (NOT `kyo.Command.spawn`) so the inner proc is not
  *      registered with any kyo `Scope`. Only the shutdown hook can kill it.
  *   2. Writes the spawned child's PID to `args(0)` (parent reads this back).
  *   3. Calls `BrowserLauncherPlatform.registerShutdownHook(proc)`.
  *   4. Calls `java.lang.System.exit(0)`. The JVM runs the hook, which kills child.
  */
private[kyo] object ShutdownHookFixture extends KyoApp:

    run {
        val sentinel = args.headMaybe.getOrElse(
            throw new RuntimeException("ShutdownHookFixture: missing sentinel-file arg")
        )

        Sync.defer {
            val pb = new java.lang.ProcessBuilder("sleep", "60")
            pb.redirectErrorStream(true)
            val jp   = pb.start()
            val proc = new JvmProcessUnsafe(jp).safe
            java.nio.file.Files.write(
                java.nio.file.Paths.get(sentinel),
                jp.pid().toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            )
            proc
        }.map { proc =>
            BrowserLauncherPlatform.registerShutdownHook(proc)
        }.andThen(Sync.defer {
            java.lang.System.exit(0)
        })
    }

end ShutdownHookFixture
