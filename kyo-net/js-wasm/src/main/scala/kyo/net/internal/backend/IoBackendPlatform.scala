package kyo.net.internal.backend

import kyo.*
import kyo.net.NetBackendUnavailableException
import kyo.net.internal.JsHandle
import kyo.net.internal.JsIoDriver
import kyo.net.internal.transport.IoDriver

/** JS Node backend, wrapping the existing `JsIoDriver` (Node owns the event loop).
  *
  * `NodeBackend` lives only in the JS source set (Node net APIs are absent elsewhere); `isAvailable = true` is the JS floor.
  */
private[net] object NodeBackend extends IoBackend:
    type Handle = JsHandle
    def name                                    = "node"
    def priority                                = 10
    def isAvailable(using AllowUnsafe): Boolean = true
    def createDriver()(using AllowUnsafe, Frame): IoDriver[JsHandle] =
        JsIoDriver.init()
end NodeBackend

private[net] object IoBackendPlatform:

    type Backend = IoBackend { type Handle = JsHandle }

    val registered: Chunk[Backend] = Chunk(NodeBackend)

    /** The selected JS backend honoring `-Dkyo.net.backend`. Always `NodeBackend`, the same driver the JS transport used before the registry
      * existed.
      */
    def selected(using AllowUnsafe, Frame): Backend =
        IoBackend.select[Backend, NetBackendUnavailableException](
            registered,
            _.name,
            _.priority,
            _.isAvailable,
            forced = Maybe(kyo.net.backend()).filter(_.nonEmpty),
            onUnavailable = NetBackendUnavailableException(_)
        ).getOrThrow

    /** Build the selected JS driver. */
    def driver()(using AllowUnsafe, Frame): IoDriver[JsHandle] =
        selected.createDriver()

end IoBackendPlatform
