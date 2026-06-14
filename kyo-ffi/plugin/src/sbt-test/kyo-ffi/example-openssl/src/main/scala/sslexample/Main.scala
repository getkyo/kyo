package sslexample

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

/** Driver that mirrors the typical OpenSSL "init once, allocate ctx, draw random bytes,
  * free ctx" flow against the stub ABI.
  */
object Main:
    def main(args: Array[String]): Unit =
        val ssl = Ffi.load[OpenSslBindings]

        // 1. Initialize the library. Real OPENSSL_init_ssl is idempotent; stub returns 1.
        val initRc = ssl.initSsl(0L, 0L)
        if initRc != 1 then
            throw new AssertionError(s"initSsl: expected 1, got $initRc")

        // 2. Obtain a method + allocate context.
        val method = ssl.tlsClientMethod(0L)
        if method == 0L then
            throw new AssertionError("tlsClientMethod returned NULL-like handle")

        val ctx = ssl.sslCtxNew(method)
        if ctx == 0L then
            throw new AssertionError("sslCtxNew returned NULL-like handle")

        // 3. Fill a 32-byte buffer with pseudo-random bytes. Real RAND_bytes uses
        //    the OpenSSL CSPRNG; stub uses a deterministic generator so the output
        //    is observable but not secret.
        Buffer.use[Byte, Unit](32) { buf =>
            val rc = ssl.randBytes(buf, 32)
            if rc != 1 then
                throw new AssertionError(s"randBytes: expected 1, got $rc")
            // Sanity: at least one byte must be non-zero. (The fresh buffer is zero-init
            // on JVM/Native/JS, any write proves the C side wrote through.)
            var i        = 0
            var nonZero  = 0
            while i < 32 do
                if buf.get(i) != 0.toByte then nonZero += 1
                i += 1
            if nonZero == 0 then
                throw new AssertionError("randBytes did not write to buffer")
        }

        // 4. Free the context.
        ssl.sslCtxFree(ctx)

        println(s"OK: method=0x${method.toHexString} ctx=$ctx init=$initRc")
    end main
end Main
