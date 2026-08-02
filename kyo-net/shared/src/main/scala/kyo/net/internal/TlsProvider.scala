package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsProviderUnavailableException
import kyo.net.internal.backend.CapabilityDescriptor
import kyo.net.internal.backend.IoBackend
import kyo.net.internal.backend.SelectionReport

/** A capability-probed TLS provider. It IS the shared [[CapabilityDescriptor]] identity with nothing added, and it reuses the SAME
  * `IoBackend.select` as the I/O registry so both registries flow through ONE selection function (selection logic never changes).
  *
  * It stays a named subtype rather than collapsing into the descriptor because it is what bounds the TLS domain: [[selectFor]] ranges over
  * `P <: TlsProvider`, which is what keeps an I/O backend out of a TLS registry even though the two carry the same identity.
  *
  * Each platform registers its TLS providers as registry entries for selection. This shared trait is FFI-free so it compiles on every
  * platform (including Wasm and JS, which have no FFI); the FFI-coupled engine-building surface lives in the `jvm-native` `TlsEngineProvider`
  * subtype.
  */
private[net] trait TlsProvider extends CapabilityDescriptor

private[net] object TlsProvider:

    /** Choose the provider to build for `config` from `registered`. A [[NetTlsConfig.tlsProvider]] pin is honored first: the provider with that
      * id is used if it is registered AND available, and FAILS CLOSED with [[NetTlsProviderUnavailableException]] otherwise (an unregistered
      * or unavailable pinned id never silently falls through to a different implementation, mirroring the forced-name contract of
      * [[IoBackend.select]]). With no pin, selection defers to the SAME `IoBackend.select` the unpinned path has always used (highest-priority
      * available provider, honoring `-Dkyo.net.tls`).
      *
      * The pinned branch resolves without going through `select`, so it used to be the one selection path that produced no diagnosis at all.
      * It now builds its own [[SelectionReport]] over the same registry, and an UNREGISTERED pin contributes a synthesized `not registered`
      * entry: the two fail-closed legs (a pin that names nothing, and a pin that names a provider this host cannot load) stay distinguishable
      * in the failure a caller catches.
      */
    def selectFor[P <: TlsProvider](registered: Chunk[P], config: NetTlsConfig, log: Log.Unsafe = Log.live.unsafe)(using
        AllowUnsafe,
        Frame
    ): P =
        config.tlsProvider match
            case Present(id) =>
                val pinned                               = Maybe.fromOption(registered.find(_.name == id))
                val probed: Chunk[SelectionReport.Entry] = registered.map(p => SelectionReport.Entry.Probed(p.name, p.priority, p.probe))
                // An unregistered pin has no descriptor to probe, so its entry is synthesized and stays distinct from a registered
                // candidate whose probe reported it unavailable.
                val entries =
                    if pinned.isEmpty then SelectionReport.Entry.NotRegistered(id) +: probed
                    else probed
                pinned match
                    case Present(provider) if provider.probe.isAvailable =>
                        val report = SelectionReport(Present(id), entries)
                        log.info(s"TlsProvider: ${report.render}")
                        provider
                    case _ =>
                        val report = SelectionReport(Absent, entries)
                        log.warn(s"TlsProvider: pinned provider '$id' is not available")
                        log.info(s"TlsProvider: ${report.render}")
                        throw NetTlsProviderUnavailableException(id, report.render)
                end match
            case Absent =>
                IoBackend.select[P, NetTlsProviderUnavailableException](
                    registered,
                    forced = Maybe(kyo.net.tls()).filter(_.nonEmpty),
                    onUnavailable = (forced, report) => NetTlsProviderUnavailableException(forced.getOrElse("<default>"), report.render),
                    log = log
                ).getOrThrow
        end match
    end selectFor

end TlsProvider
