package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig

/** A [[TlsProvider]] that also builds an in-process [[TlsEngine]]. [[TlsEngine]] is FFI-coupled (its buffer methods take `kyo.ffi.Buffer`), and
  * kyo-ffi is available on all four platforms, so this subtype is in `shared` and every platform that terminates TLS in-process drives it:
  * BoringSSL on JVM, Native, JS, and Wasm; the JDK `SslEngine` on JVM; system OpenSSL on Native. The JS/Wasm Node transport terminates TLS in
  * Node instead and registers a selection-only [[TlsProvider]] (`NodeTlsProvider`), the one provider that never builds an engine.
  *
  * It refines [[TlsProvider]] rather than the shared capability identity directly, because `TlsProvider` is the domain bound TLS selection
  * ranges over (`TlsProvider.selectFor[P <: TlsProvider]`), and that bound is what keeps an I/O backend out of a TLS registry. Selection
  * stays on that shared surface; the selected provider's [[createEngine]] builds the [[TlsEngine]] used for the connection.
  */
private[net] trait TlsEngineProvider extends TlsProvider:

    /** Build the provider's TLS engine. Called once selection wins. */
    def createEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine

end TlsEngineProvider
