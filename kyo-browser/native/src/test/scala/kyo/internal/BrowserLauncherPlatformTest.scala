package kyo.internal

import kyo.*

/** Native platform tests for [[BrowserLauncherPlatform]].
  *
  * Native runs the `jvm-native` implementation, which registers a `Runtime.addShutdownHook` thread. Scala Native has no
  * `java.lang.ApplicationShutdownHooks` table to inspect the way the JVM suite does, so these leaves observe the registered hook
  * itself: the runtime holds it (removing it succeeds), it is not a daemon, and running it kills a live process.
  */
class BrowserLauncherPlatformTest extends BaseBrowserTest:

    "registerShutdownHook leaves a non-daemon hook registered with the runtime" in {
        Scope.run {
            // `true` is a POSIX no-op binary that exits 0 immediately.
            Command("true").spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHookThread(proc).map { hook =>
                    // Removing the hook succeeds only if the runtime holds it; it also keeps this hook from running at exit.
                    val removed = Runtime.getRuntime.removeShutdownHook(hook)
                    assert(removed, "the hook was not registered with the runtime")
                    assert(!hook.isDaemon, "a shutdown hook must not be a daemon thread")
                }
            }
        }
    }

    "the registered hook kills a process that is still alive" in {
        Scope.run {
            Command("sleep", "30").spawn.map { proc =>
                for
                    hook    <- BrowserLauncherPlatform.registerShutdownHookThread(proc)
                    _       <- Sync.defer(Runtime.getRuntime.removeShutdownHook(hook))
                    aliveAt <- proc.isAlive
                    // Running the hook's body directly is what the runtime does at exit, minus the exit.
                    _    <- Sync.defer(hook.run())
                    exit <- proc.waitFor
                    dead <- proc.isAlive.map(!_)
                yield
                    assert(aliveAt, "the process must be alive before the hook runs")
                    assert(dead && !exit.isSuccess, s"the hook must kill the process, exit $exit")
            }
        }
    }

end BrowserLauncherPlatformTest
