package kyo.ffi.it

import kyo.*
import kyo.ffi.*

class ItOpaqueTest extends ItTestBase:

    private lazy val bindings = Ffi.load[ItOpaqueBindings]

    "create handle returns non-null (bare Handle)" in {
        val h = bindings.kyo_it_create_handle(42)
        // A non-null handle reads back the value it was created with.
        val read = bindings.kyo_it_read_handle(h)
        bindings.kyo_it_destroy_handle(h)
        assert(read == 42)
    }

    "read handle returns stored value" in {
        val h    = bindings.kyo_it_create_handle(42)
        val read = bindings.kyo_it_read_handle(h)
        bindings.kyo_it_destroy_handle(h)
        assert(read == 42)
    }

    "roundtrip create-read-destroy" in {
        val h = bindings.kyo_it_create_handle(123)
        val v = bindings.kyo_it_read_handle(h)
        bindings.kyo_it_destroy_handle(h)
        assert(v == 123)
    }

    "bare Handle returning NULL throws FfiNullPointer" in {
        val ex = intercept[FfiNullPointer] {
            bindings.kyo_it_null_handle_bare()
        }
        assert(ex.getMessage.contains("kyo_it_null_handle_bare"))
    }

    "bare Handle returning NULL from a @Ffi.blocking call surfaces FfiNullPointer as a fiber failure on every backend" in {
        // The blocking call's marshal decodes a non-nullable Handle from a NULL return and throws FfiNullPointer.
        // On JVM/Native that throw propagates synchronously out of the carrier call and is captured by the < Async
        // evaluation; on JS the marshal runs inside koffi's libuv completion callback, so before the BlockingBridge
        // fix a thrown FfiNullPointer escaped as an uncaught error, terminated the Node process, and left the fiber
        // forever pending. Every backend must instead surface it as an observable fiber Panic the consumer can catch.
        Abort.run[Any](bindings.kyo_it_null_handle_bare_blocking().safe.get).map {
            case Result.Panic(ex) =>
                assert(ex.isInstanceOf[FfiNullPointer], s"expected FfiNullPointer, got ${ex.getClass.getName}: $ex")
                assert(ex.getMessage.contains("kyo_it_null_handle_bare_blocking"), s"unexpected message: ${ex.getMessage}")
            case other =>
                fail(s"expected an observable Panic(FfiNullPointer) fiber failure, got $other")
        }
    }

    "Maybe[Handle] returning NULL yields Absent" in {
        val result: Maybe[Ffi.Handle[ItHandle]] = bindings.kyo_it_null_handle()
        assert(result == Absent)
    }

    "Maybe[Handle] returning non-null yields Present" in {
        val result: Maybe[Ffi.Handle[ItHandle]] = bindings.kyo_it_create_handle_maybe(77)
        result match
            case Present(h) =>
                val read = bindings.kyo_it_read_handle(h)
                bindings.kyo_it_destroy_handle(h)
                assert(read == 77)
            case Absent =>
                fail("Expected Present but got Absent")
        end match
    }

    // Type safety: Ffi.Handle[ItHandle] and Ffi.Handle[OtherType] are distinct at compile time.
    // This is enforced by Scala's type system, passing a Ffi.Handle[ItHandle] where a
    // Ffi.Handle[OtherType] is expected produces a compile error. No runtime test needed.

end ItOpaqueTest
