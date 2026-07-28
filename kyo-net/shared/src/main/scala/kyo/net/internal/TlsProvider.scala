package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.internal.backend.IoBackend

/** A capability-probed TLS provider. Identical selection shape to `IoBackend`, and it reuses the SAME `IoBackend.select` so both registries
  * flow through ONE selection function (selection logic never changes).
  *
  * Each platform registers its TLS providers as registry entries for selection. This shared trait is FFI-free so it compiles on every
  * platform (including Wasm and JS, which have no FFI); the FFI-coupled engine-building surface lives in the `jvm-native` `TlsEngineProvider`
  * subtype.
  */
private[net] trait TlsProvider:

    /** Stable id matched by `-Dkyo.net.tls` ("boringssl" | "jdk" | "openssl" | "node"). */
    def name: String

    /** Higher wins. boringssl=30, openssl=20, jdk/node=10 (Backend & TLS matrix). */
    def priority: Int

    /** Cheap capability probe. MUST NOT throw. */
    def isAvailable(using AllowUnsafe): Boolean

end TlsProvider

private[net] object TlsProvider:

    /** Choose the provider to build for `config` from `registered`. A [[NetTlsConfig.tlsProvider]] pin is honored first: the provider with that
      * id is used if it is registered AND available, and FAILS CLOSED with [[NetTlsProviderUnavailableException]] otherwise (an unregistered
      * or unavailable pinned id never silently falls through to a different implementation, mirroring the forced-name contract of
      * [[IoBackend.select]]). With no pin, selection defers to the SAME `IoBackend.select` the unpinned path has always used (highest-priority
      * available provider, honoring `-Dkyo.net.tls`).
      */
    def selectFor[P <: TlsProvider](registered: Chunk[P], config: NetTlsConfig)(using AllowUnsafe, Frame): P =
        config.tlsProvider match
            case Present(id) =>
                Maybe.fromOption(registered.find(_.name == id)) match
                    case Present(p) if p.isAvailable => p
                    case Present(_)                  => throw NetTlsProviderUnavailableException(id)
                    case Absent                      => throw NetTlsProviderUnavailableException(id)
            case Absent =>
                IoBackend.select[P, NetTlsProviderUnavailableException](
                    registered,
                    _.name,
                    _.priority,
                    _.isAvailable,
                    forced = Maybe(kyo.net.tls()).filter(_.nonEmpty),
                    onUnavailable = _ => NetTlsProviderUnavailableException("<default>")
                ).getOrThrow
    end selectFor

end TlsProvider
