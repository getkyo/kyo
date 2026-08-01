package kyo.net.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** The BoringSSL TLS provider, the priority-30 primary on every platform that stages BoringSSL (JVM, Native, JS, and Wasm). Lives in `shared`:
  * one provider over the one shared [[BoringSslBindings]], driving the in-process TLS engine on all four platforms (via Panama on JVM,
  * `@extern` on Native, koffi on JS/Wasm). The JS/Wasm Node transport is the alternative that terminates TLS in Node instead.
  *
  * It supplies the BoringSSL binding, name, priority, and library id to [[SslLibProvider]], which carries the shared engine construction,
  * config application, client-identity binding, and capability probe. A host without the staged bundle probes as not bundled, so TLS falls
  * through to the JVM `jdk` floor or the Native `openssl` fallback and the report names the library that was missing.
  */
private[net] object BoringSslProvider extends SslLibProvider:

    def name = "boringssl"

    def priority = 30

    def libraryIds: Chunk[String] = Chunk(BoringSslBindings.library)

    private[internal] def lib(using AllowUnsafe): SslLibBindings = Ffi.load[BoringSslBindings]

end BoringSslProvider
