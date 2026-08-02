package kyo.net.internal.backend

import java.nio.channels.spi.SelectorProvider
import kyo.*
import kyo.net.Transport
import kyo.net.internal.NioHandle
import kyo.net.internal.NioIoDriver
import kyo.net.internal.NioTransport

/** JVM-only pure-JDK floor backend, wrapping the existing `NioIoDriver` (one `java.nio.channels.Selector`).
  *
  * JVM ONLY because it imports `java.nio.channels.Selector` (absent on Native/JS). An unconditionally available probe is sound ONLY here: a
  * shared backend that always probed available would fail to compile on Native/JS, which supply their own floor (epoll/kqueue, Node).
  *
  * It is a registry [[Entry]] directly, so the registry lists this object rather than a wrapper that forwards its identity to it. `build`
  * constructs the existing `NioTransport` exactly as the JVM transport did before the registry held posix backends, which keeps
  * `NioTransport` (and its concrete `NioIoDriver`) reachable as the always-available floor.
  */
private[net] object NioBackend extends Entry:
    type Handle = NioHandle

    def name = "nio"

    def priority = 10

    /** Pure JDK: no native library to stage, so nothing can be missing for this host. */
    def libraryIds: Chunk[String] = Chunk.empty

    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
        // Touch the JDK Selector provider so the probe reflects an actually-usable NIO stack. A JDK that cannot supply one throws, which
        // CapabilityDescriptor.probe reports rather than letting escape; on every working JDK this is the floor and always available.
        val _ = SelectorProvider.provider()
        CapabilityOutcome.Available
    end doProbe

    // Covariant return: the trait declares IoDriver[Handle]; this floor produces the concrete NioIoDriver the JVM
    // transport drives (it calls NioIoDriver-specific channel-registration methods), so the concrete type is preserved.
    def createDriver()(using AllowUnsafe, Frame): NioIoDriver =
        NioIoDriver.init()

    def build()(using AllowUnsafe, Frame): Transport =
        NioTransport.init()
end NioBackend
