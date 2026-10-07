package kyo.net.internal

import java.nio.channels.Selector
import kyo.*

/** Test-tree construction helpers for [[NioIoDriver]]. They wrap the driver's package-private constructor so a test can build a driver over a
  * chosen selector without any production test factory. Production builds drivers via `NioIoDriver.init()`.
  */
object TestNioDrivers:

    /** Build a [[NioIoDriver]] over a caller-supplied selector. */
    def forSelector(selector: Selector)(using AllowUnsafe): NioIoDriver =
        new NioIoDriver(selector)

end TestNioDrivers
