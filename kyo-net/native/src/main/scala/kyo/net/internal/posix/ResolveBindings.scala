package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** The blocking hostname-resolution shim (`kyo_net_resolve.c`) bound through kyo-ffi, used only on Scala Native.
  *
  * `getaddrinfo` cannot be bound directly: its `struct addrinfo **` out-parameter is a recursive linked list, which the codegen
  * `TypeValidator` rejects (circular-reference detection). The `kyo_net_resolve` C shim keeps that recursive structure entirely internal and
  * exposes a flat signature: a host string plus a family hint in, the first matching raw address bytes plus the resolved family out. So the
  * binding here marshals only primitives and `Buffer` out-parameters.
  *
  * On JVM the system resolver is reached through `java.net.InetAddress` instead (see the JVM `SystemResolver`), so this binding lives in the
  * Native source set only and the Native codegen is the only one that emits it. On JS the posix transport is never used (Node does its own
  * async DNS), so no JS binding exists.
  *
  * `getaddrinfo` blocks (it consults `/etc/hosts`, the resolver, and the network), so the method carries `@Ffi.blocking`: the Native downcall
  * runs on a scheduler carrier the `BlockingMonitor` compensates for (it drains the parked worker's queue), so the calling fiber suspends and
  * no carrier is permanently starved. The result is surfaced as a `Fiber.Unsafe`; the `SystemResolver` consumer reads it via `done()`/`poll()`
  * (the `@Ffi.blocking` shim completes inline on Native) or attaches `onComplete`, never by blocking on the result.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause: each native call is a side effect tracked by
  * the caller.
  */
private[net] trait ResolveBindings extends Ffi:

    /** `int kyo_net_resolve(const char* host, int family_hint, unsigned char* out_addr, int* out_family)`.
      *
      * Resolves `host` to one A or AAAA address. `familyHint` is `AF_INET`, `AF_INET6`, or `AF_UNSPEC` (0), advisory only: the shim asks
      * `getaddrinfo` with `AF_UNSPEC` (never restricting to the hint, so a v6-only host still resolves, matching JVM) and then PREFERS the first
      * result whose family equals the hint, falling back to the first A/AAAA of any family. On success `outAddr` receives the raw network-order
      * address bytes (4 for `AF_INET`, 16 for `AF_INET6`) and `outFamily` receives the resolved family; the call returns 0. On failure it
      * returns the non-zero `getaddrinfo` error (an `EAI_*` value) and the out-parameters are unchanged. `outAddr` must be at least 16 bytes;
      * `outFamily` is a 1-element `Buffer[Int]`. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def kyo_net_resolve(host: String, familyHint: Int, outAddr: Buffer[Byte], outFamily: Buffer[Int])(using
        AllowUnsafe
    ): Fiber.Unsafe[Int, Any]

end ResolveBindings

private[net] object ResolveBindings extends Ffi.Config(
        library = "kyonet_posix_uring",
        headers = Chunk("netdb.h"),
        nativeBundled = true
    )
