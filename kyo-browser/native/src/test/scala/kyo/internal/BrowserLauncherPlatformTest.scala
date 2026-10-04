package kyo.internal

import kyo.*

/** Native platform tests for [[BrowserLauncherPlatform]].
  *
  * Native runs the `jvm-native` implementation, which registers a `Runtime.addShutdownHook` thread. The JVM suite inspects the
  * registered hook through `java.lang.ApplicationShutdownHooks` reflection, which Scala Native does not have, so this suite only proves
  * the registration links and runs on Native without raising.
  */
class BrowserLauncherPlatformTest extends BaseBrowserTest:

    "registerShutdownHook registers on Native without raising" in {
        Scope.run {
            // `true` is a POSIX no-op binary that exits 0 immediately.
            Command("true").spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHook(proc).map { _ =>
                    ()
                }
            }
        }
    }

end BrowserLauncherPlatformTest
