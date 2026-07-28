package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig

/** A [[TlsProvider]] that also builds an in-process [[TlsEngine]]. Lives in the `jvm-native` source set because [[TlsEngine]] is FFI-coupled
  * (its buffer methods take `kyo.ffi.Buffer`), so this subtype only exists on the platforms that terminate TLS in-process (JVM and Native).
  * The JS/Wasm Node backend terminates TLS itself and registers a selection-only [[TlsProvider]], so it never sees this trait.
  *
  * Selection stays on the shared [[TlsProvider]] surface (name/priority/isAvailable); the selected provider's [[createEngine]] builds the
  * [[TlsEngine]] used for the connection.
  */
private[net] trait TlsEngineProvider extends TlsProvider:

    /** Build the provider's TLS engine. Called once selection wins. */
    def createEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine

end TlsEngineProvider
