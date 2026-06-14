package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.*

class ItHandle

trait ItOpaqueBindings extends Ffi:
    def kyo_it_create_handle(value: Int)(using AllowUnsafe): Ffi.Handle[ItHandle]
    def kyo_it_read_handle(h: Ffi.Handle[ItHandle])(using AllowUnsafe): Int
    def kyo_it_destroy_handle(h: Ffi.Handle[ItHandle])(using AllowUnsafe): Unit
    def kyo_it_null_handle()(using AllowUnsafe): Maybe[Ffi.Handle[ItHandle]]
    def kyo_it_null_handle_bare()(using AllowUnsafe): Ffi.Handle[ItHandle]
    def kyo_it_create_handle_maybe(value: Int)(using AllowUnsafe): Maybe[Ffi.Handle[ItHandle]]

    // A @Ffi.blocking non-nullable Handle return that yields NULL: the marshal throws FfiNullPointer inside the
    // async completion (on JS, koffi's libuv callback). Exercises that a blocking marshal throw is captured into
    // the fiber result rather than escaping (which on JS would terminate the Node process). See BlockingBridge.
    @Ffi.blocking
    def kyo_it_null_handle_bare_blocking()(using AllowUnsafe): Fiber.Unsafe[Ffi.Handle[ItHandle], Any]
end ItOpaqueBindings

object ItOpaqueBindings extends Ffi.Config(library = "kyo_it_bundled")
