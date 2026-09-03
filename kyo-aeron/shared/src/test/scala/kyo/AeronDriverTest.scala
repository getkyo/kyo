package kyo

/** Runtime coverage for the driver-only launch path.
  *
  * `AeronDriver.launch` had none: the only reference to it in the suite was a type ascription, and a kyo
  * computation is a value, so ascribing one never starts a driver. Everything the API promises about the
  * lifetime it manages was therefore unexercised, on a path that starts a real C media driver and its
  * conductor threads.
  */
class AeronDriverTest extends Test:

    "launch starts a driver and binds its lifetime to the scope" in {
        Scope.run {
            AeronDriver.launch().map { driver =>
                Sync.Unsafe.defer {
                    val dump = kyo.internal.Diagnostics.dumpAll()
                    assert(
                        dump.contains(driver.directory.unsafe.show),
                        "a launched driver does not name its directory in the diagnostics dump"
                    )
                    driver.directory.unsafe.show
                }
            }
        }.map { dir =>
            Sync.Unsafe.defer {
                assert(
                    !kyo.internal.Diagnostics.dumpAll().contains(dir),
                    "the scope exited without releasing the driver's diagnostics entry"
                )
            }
        }
    }

    /** The API's contract is that a caller-owned driver is closed exactly once, and closing it twice used to
      * be a native fault rather than a no-op. A caller that releases on an error path and again at scope exit
      * is the ordinary shape that hit it, so the repeat is pinned here as harmless.
      */
    "a caller-owned driver tolerates being closed twice" in {
        AeronDriver.launchUnscoped().map { driver =>
            Sync.Unsafe.defer {
                driver.unsafe.close()
                driver.unsafe.close()
                succeed
            }
        }
    }

end AeronDriverTest
