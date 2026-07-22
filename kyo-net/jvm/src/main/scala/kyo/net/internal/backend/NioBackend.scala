package kyo.net.internal.backend

import java.nio.channels.spi.SelectorProvider
import kyo.*
import kyo.net.internal.NioHandle
import kyo.net.internal.NioIoDriver
import kyo.net.internal.transport.IoDriver

/** JVM-only pure-JDK floor backend, wrapping the existing `NioIoDriver` (one `java.nio.channels.Selector`).
  *
  * JVM ONLY because it imports `java.nio.channels.Selector` (absent on Native/JS). `isAvailable = true` is sound ONLY here: a shared
  * backend with `isAvailable = true` would fail to compile on Native/JS, which supply their own floor (epoll/kqueue, Node). io_uring,
  * epoll, and kqueue backends are added on JVM in Phases 6-7; `nio` is the floor until then and remains the always-available fallback after.
  */
private[net] object NioBackend extends IoBackend:
    type Handle = NioHandle

    def name = "nio"

    def priority = 10

    // JVM-only floor: pure JDK, always available regardless of kernel/container/native-access.
    def isAvailable(using AllowUnsafe): Boolean =
        // Touch the JDK Selector provider so the probe reflects an actually-usable NIO stack.
        val _ = SelectorProvider.provider()
        true
    end isAvailable

    // Covariant return: the trait declares IoDriver[Handle]; this floor produces the concrete NioIoDriver the JVM
    // transport drives (it calls NioIoDriver-specific channel-registration methods), so the concrete type is preserved.
    def createDriver()(using AllowUnsafe, Frame): NioIoDriver =
        NioIoDriver.init()
end NioBackend
