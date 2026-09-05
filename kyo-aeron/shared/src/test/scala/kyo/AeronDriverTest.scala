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

    /** The driver is configured not to delete its own directory on shutdown, so the removal in `State.close`
      * is the only one rather than a backstop behind Aeron's. That makes a directory outliving its scope a
      * leak with nothing else to catch it.
      *
      * The parent is asserted gone too, and that is what pins the nesting: the driver is handed a path it
      * creates itself inside a temp directory the launch allocated, so the parent is that temp directory and
      * goes with it. Hand Aeron the temp directory directly and this parent becomes the system temp root,
      * which is still there afterwards.
      */
    "the scope removes the driver's directory and the temp directory holding it" in {
        Scope.run {
            AeronDriver.launch().map(_.directory)
        }.map { dir =>
            Path.run(dir.exists).map { survived =>
                assert(!survived, s"the scope exited leaving the driver directory behind: ${dir.unsafe.show}")
                dir.parent match
                    case Present(root) =>
                        Path.run(root.exists).map { rootSurvived =>
                            assert(
                                !rootSurvived,
                                s"the scope exited leaving the directory holding the driver behind: ${root.unsafe.show}"
                            )
                        }
                    case Absent =>
                        fail("the driver directory has no parent, so it was not created inside a temp directory")
                end match
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
