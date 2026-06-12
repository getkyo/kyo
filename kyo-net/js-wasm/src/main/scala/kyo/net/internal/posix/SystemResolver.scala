package kyo.net.internal.posix

import kyo.*

/** JS system resolver: an inert stub. The posix transport (`PosixTransport`) is compiled for JS because it lives in the shared source set, but
  * it is never INSTANTIATED on JS: the JS transport is `JsTransport`, which calls Node's `net.connect(port, host)` directly so Node performs
  * its own async (libuv-offloaded) DNS resolution and never routes through `HostResolver`/`encodeInet`. So this resolver is never invoked in
  * practice; it exists only to satisfy the shared [[HostResolver]] seam's reference to `SystemResolver.resolveRaw`.
  *
  * If it were ever called it fails `Closed` cleanly (it must not block Node's single event-loop thread, so it deliberately does no resolution):
  * hostname resolution on JS is Node's responsibility, performed inside `JsTransport.connect`.
  */
private[net] object SystemResolver:

    /** Always fails `Closed`: the JS posix transport is never used, so this is unreachable in practice. It does NOT call any blocking resolver
      * (that would freeze Node's event loop); JS hostname resolution is handled by Node inside `JsTransport.connect`. Returns an
      * already-complete `Fiber.Unsafe.fromResult` stub; no carrier is spawned.
      */
    def resolveRaw(host: String, familyHint: Int)(using Frame): Fiber.Unsafe[Result[Closed, HostResolver.Resolved], Any] =
        // Unsafe: inert JS stub; it only builds a failed Fiber.Unsafe.fromResult (no side effect), so the danger bridge is harmless.
        import AllowUnsafe.embrace.danger
        // JS: inert stub; PosixTransport is never instantiated on JS, so this is unreachable in practice.
        Fiber.Unsafe.fromResult(Result.succeed(
            Result.fail(Closed(
                "HostResolver",
                summon[Frame],
                s"resolve: posix host resolution is not used on JS (Node resolves $host inside JsTransport.connect)"
            )): Result[Closed, HostResolver.Resolved]
        ))
    end resolveRaw

end SystemResolver
