package kyo.net.internal

import java.net.InetAddress
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream
import kyo.AllowUnsafe
import kyo.Promise
import kyo.Result

/** The test JVM's name resolver (registered in `META-INF/services`): a host name under [[ParkingResolverProvider.Domain]] resolves to
  * 127.0.0.1 only after the test releases its lookup, and every other name goes to the JDK's built-in resolver. It stands in for a system
  * resolver that answers slowly, so a test can see which thread waits on the lookup.
  *
  * The owning probe releases the native gate on completion or interruption, so resolver completion cannot race a watchdog answer.
  */
final class ParkingResolverProvider extends InetAddressResolverProvider:

    def get(configuration: InetAddressResolverProvider.Configuration): InetAddressResolver =
        val builtin = configuration.builtinResolver()
        new InetAddressResolver:
            def lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream[InetAddress] =
                if host.endsWith(ParkingResolverProvider.Domain) then
                    // Unsafe: the JDK invokes this resolver callback outside an effect.
                    import AllowUnsafe.embrace.danger
                    val lookup = ParkingResolverProvider.lookup(host)
                    lookup.started.completeDiscard(Result.succeed(()))
                    // deviation: the JDK SPI must block like a real resolver; the probe releases this gate in Sync.ensure, including on interruption.
                    lookup.released.await()
                    lookup.resolutionComplete.set(true)
                    Stream.of(InetAddress.getByAddress(host, Array[Byte](127, 0, 0, 1)))
                else builtin.lookupByName(host, policy)

            def lookupByAddress(addr: Array[Byte]): String = builtin.lookupByAddress(addr)
        end new
    end get

    def name(): String = "kyo-net parking test resolver"

end ParkingResolverProvider

object ParkingResolverProvider:

    val Domain = ".parking.kyo-net.test"

    final class Lookup private[ParkingResolverProvider] (val started: Promise.Unsafe[Unit, Any]):
        val released           = new CountDownLatch(1)
        val resolutionComplete = new AtomicBoolean(false)

    private val lookups    = new ConcurrentHashMap[String, Lookup]()
    private val nextHostId = new AtomicLong(0)

    /** A fresh name bypasses both the JDK's answer cache and the transport's resolver cache. */
    def host(prefix: String)(using AllowUnsafe): String = prefix + "-" + nextHostId.incrementAndGet() + Domain

    /** The lookup state for `host`, shared by the resolver and the test. Each test uses its own host so the JDK's answer cache never
      * short-circuits a lookup it means to park.
      */
    def lookup(host: String)(using AllowUnsafe): Lookup = lookups.computeIfAbsent(host, _ => new Lookup(Promise.Unsafe.init()))

end ParkingResolverProvider
