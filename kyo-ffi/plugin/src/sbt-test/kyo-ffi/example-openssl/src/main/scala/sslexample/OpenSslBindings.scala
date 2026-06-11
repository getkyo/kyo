package sslexample

import kyo.AllowUnsafe
import kyo.ffi.*

/** Worked example: OpenSSL-style bindings.
  *
  * In a production binding against real OpenSSL:
  *   - Drop `ffiCSources` and set `ffiLinkLibs := Seq("ssl", "crypto")`.
  *   - Change `symbolPrefix` to `""`.
  *   - Keep every method signature below unchanged.
  *
  * The stub C source file in this project has the same ABI as the real library
  * (opaque handles represented as `long`), so the binding path is fully exercised
  * without depending on OpenSSL being installed on the build host.
  */
trait OpenSslBindings extends Ffi:
    // TLS_client_method(unused) -> const SSL_METHOD* (opaque handle as Long).
    // The real symbol is nullary; we pass an ignored `Long` so the codegen path
    // uses the standard "with-args" FunctionDescriptor branch (emitter quirk:
    // zero-arg methods are not yet supported in this build).
    def tlsClientMethod(unused: Long)(using AllowUnsafe): Long

    // OPENSSL_init_ssl(uint64_t opts, const void *settings) -> int
    // We model the NULL `settings` argument as 0L and cast in C.
    def initSsl(opts: Long, settings: Long)(using AllowUnsafe): Int

    // SSL_CTX_new(method) -> SSL_CTX* (opaque handle as Long).
    def sslCtxNew(method: Long)(using AllowUnsafe): Long

    // SSL_CTX_free(ctx) -> void
    def sslCtxFree(ctx: Long)(using AllowUnsafe): Unit

    // RAND_bytes(unsigned char *buf, int num) -> int (1 on success, 0 on failure)
    // `Buffer[Byte]` is zero-copy: the C side writes directly into the caller-owned buffer.
    def randBytes(buf: Buffer[Byte], num: Int)(using AllowUnsafe): Int
end OpenSslBindings

object OpenSslBindings extends Ffi.Config(library = "kyo_openssl_stub", symbolPrefix = "kyo_openssl_")
