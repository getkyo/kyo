package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Cross-carrier publication of the post-construction `Connection` upgrade wiring.
  *
  * The transport assigns `upgradeFn` and `isServerOrigin` on the connection AFTER construction, on the creating fiber; a later STARTTLS upgrade
  * reads them through `doUpgradeToTls` on a DIFFERENT carrier (the upgrade runs on whatever fiber calls `Transport.upgradeToTls`). Without the
  * `@volatile` fence on these fields, a reader on a distinct carrier could observe the pre-assignment value (`Absent`) and take the
  * `NetNotUpgradableException` fallback even though the function was published. This drives that handoff deterministically: the writer publishes
  * and opens a `Latch`; a reader fiber, started on its own carrier and parked on the latch, reads the fields only after the latch releases, so
  * the latch (never a sleep) is the happens-before witness. The assertion is on the OBSERVED upgrade outcome and the OBSERVED origin flag, not on
  * the latch itself.
  */
class ConnectionUpgradePublishTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A no-op `IoDriver` good enough to build a `Connection` whose pumps are never started. The upgrade-publication path under test reads only
      * the `Connection` fields, never the driver, so every method is an inert stub.
      */
    private object NoopDriver extends IoDriver[Unit]:
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit      = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                          = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                        = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                   = ()
        def close()(using AllowUnsafe, Frame): Unit                                                                     = ()
        def label: String                                                                                               = "NoopDriver"
        def handleLabel(handle: Unit): String                                                                           = "noop"
    end NoopDriver

    "upgrade observes the published upgradeFn and isServerOrigin across a carrier handoff" in {
        val connection = Connection.init[Unit]((), NoopDriver, 1)

        // The success sentinel the wired upgradeFn returns: a completed Fiber whose value is the same connection (the test only needs to tell
        // "the wired function ran" apart from "the NetNotUpgradableException fallback ran").
        val upgraded: kyo.net.Connection = connection

        for
            latch <- Latch.init(1)
            observed <- Fiber.init {
                // Distinct carrier: this body parks on the latch, so it reads upgradeFn/isServerOrigin only after the writer published them.
                latch.await.map { _ =>
                    val origin = connection.isServerOrigin
                    val result = connection.doUpgradeToTls(NetTlsConfig.default, summon[Frame]).safe.get
                    result.map(conn => (origin, conn))
                }
            }
            // Writer side: publish the wiring on the creating fiber, then release the latch.
            _ <- Sync.defer {
                connection.isServerOrigin = true
                connection.upgradeFn = Present { (_, frame) =>
                    given Frame = frame
                    Fiber.Unsafe.fromResult(Result.succeed(upgraded))
                }
            }
            _      <- latch.release
            result <- observed.get
        yield
            val (origin, conn) = result
            // The wired function ran (the upgrade succeeded with the sentinel connection), not the NetNotUpgradableException fallback, and the
            // distinct carrier observed the published server origin.
            assert(origin == true)
            assert(conn eq upgraded)
        end for
    }

    "doUpgradeToTls falls back to NetNotUpgradableException when no upgradeFn is published" in {
        val connection = Connection.init[Unit]((), NoopDriver, 1)
        for result <- Abort.run[NetException](connection.doUpgradeToTls(NetTlsConfig.default, summon[Frame]).safe.get)
        yield assert(result.isFailure)
    }

end ConnectionUpgradePublishTest
