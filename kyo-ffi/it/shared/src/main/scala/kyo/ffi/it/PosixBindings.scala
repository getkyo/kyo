package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** POSIX process / time bindings exercised on JVM + Native only (Scala.js lacks portable POSIX for `process.h`-family calls). Lives in
  * `jvm-native/src/main/` so the JS cross-subproject never sees it.
  *
  * `getenv` is bound here via `Borrowed[String]` to exercise top-level borrowed-String returns. The C signature is
  * `char* getenv(const char*)`; the pointer is owned by the environment and must not be freed, so the generator wraps the return with
  * `Scratch.readCStringBounded` which COPIES the bytes into a Scala String. The borrowed original C memory remains the environment's.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)`. The 6 POSIX assertions are extended with getenv
  * coverage by `PosixSpec`.
  */
trait PosixBindings extends Ffi:
    /** Current process id. */
    def getpid()(using AllowUnsafe): Int

    /** Seconds since the Unix epoch. The C signature is `time_t time(time_t*)`. We bind the out-pointer parameter as `Long` (rather than a
      * buffer) and pass `0L` for NULL. On 64-bit System V / ARM64 ABIs pointer and long share the same argument register so this is
      * equivalent to passing `NULL`; on 32-bit systems this binding would not work (kyo-ffi targets 64-bit only).
      */
    def time(tloc: Long)(using AllowUnsafe): Long

    /** Return the value of environment variable `name`, or `null` if the variable is not set.
      *
      * The C side (libc `getenv`) hands back a pointer to environment-owned storage, the caller must not free it. kyo-ffi's borrowed
      * return semantics COPY the NUL-terminated bytes into a Scala String so the returned value is independently owned.
      */
    def getenv(name: String)(using AllowUnsafe): Ffi.Borrowed[String]
end PosixBindings

object PosixBindings extends Ffi.Config(library = "c")
