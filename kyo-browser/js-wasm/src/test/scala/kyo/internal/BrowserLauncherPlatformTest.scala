package kyo.internal

import kyo.*

/** JS platform tests for [[BrowserLauncherPlatform]].
  *
  * Lives in the JS test tree because it pins the JS-specific [[BrowserLauncherPlatform]] implementation (a no-op), and each platform has
  * its own distinct `BrowserLauncherPlatform`, so this contract cannot move to `shared/`.
  *
  * The JS implementation is a no-op. This test verifies that the no-op compiles and runs without raising an exception.
  */
class BrowserLauncherPlatformTest extends Test:

    "registerShutdownHook is a no-op on JS (no exception raised)" in run {
        Scope.run {
            // `true` is a POSIX no-op binary that exits 0 immediately.
            Command("true").spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHook(proc).map { _ =>
                    succeed
                }
            }
        }
    }

end BrowserLauncherPlatformTest
