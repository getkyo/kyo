package kyo.net.internal

import java.net.InetAddress
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/** The test JVM's name resolver (registered in `META-INF/services`): a host name under [[ParkingResolverProvider.Domain]] resolves to
  * 127.0.0.1 only after the test releases its lookup, and every other name goes to the JDK's built-in resolver. It stands in for a system
  * resolver that answers slowly, so a test can see which thread waits on the lookup.
  *
  * The parked lookup blocks its thread on purpose, as the system resolver does; it is bounded so a test that never releases it cannot
  * hang the run.
  */
final class ParkingResolverProvider extends InetAddressResolverProvider:

    def get(configuration: InetAddressResolverProvider.Configuration): InetAddressResolver =
        val builtin = configuration.builtinResolver()
        new InetAddressResolver:
            def lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream[InetAddress] =
                if host.endsWith(ParkingResolverProvider.Domain) then
                    val lookup = ParkingResolverProvider.lookup(host)
                    lookup.started.countDown()
                    kyo.discard(lookup.released.await(30, TimeUnit.SECONDS))
                    Stream.of(InetAddress.getByAddress(host, Array[Byte](127, 0, 0, 1)))
                else builtin.lookupByName(host, policy)

            def lookupByAddress(addr: Array[Byte]): String = builtin.lookupByAddress(addr)
        end new
    end get

    def name(): String = "kyo-net parking test resolver"

end ParkingResolverProvider

object ParkingResolverProvider:

    val Domain = ".parking.kyo-net.test"

    final class Lookup:
        val started  = new CountDownLatch(1)
        val released = new CountDownLatch(1)

    private val lookups = new ConcurrentHashMap[String, Lookup]()

    /** The lookup state for `host`, shared by the resolver and the test. Each test uses its own host so the JDK's answer cache never
      * short-circuits a lookup it means to park.
      */
    def lookup(host: String): Lookup = lookups.computeIfAbsent(host, _ => new Lookup)

end ParkingResolverProvider
