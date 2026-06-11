package kyo.internal

import kyo.*

/** Native platform tests for [[BrowserLauncherPlatform]].
  *
  * Lives in the Native test tree because it pins the Native-specific [[BrowserLauncherPlatform]] implementation (a no-op), and each
  * platform has its own distinct `BrowserLauncherPlatform`, so this contract cannot move to `shared/`.
  *
  * The Native implementation is a no-op. This test verifies that the no-op compiles and runs without raising an exception.
  */
class BrowserLauncherPlatformTest extends BaseBrowserTest:

    "registerShutdownHook is a no-op on Native (no exception raised)" in {
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
