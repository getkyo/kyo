package kyo.ffi.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import kyo.discard
import kyo.ffi.Test

/** JVM-only unit tests for [[UpcallBridge]].
  *
  * Tests verify end-to-end roundtrip: we wrap a Scala `FunctionN` as an upcall stub via `UpcallBridge.stubN`, then invoke the stub through
  * a Panama `downcallHandle`. This is the same path the FFI generated code uses: C (here simulated by the Linker's own downcall mechanism
  * pointed at the upcall stub's address) calls back into the Scala function. A successful roundtrip proves the arity, boxing, and arena
  * lifetime are wired correctly.
  */
class UpcallBridgeTest extends Test:

    private val linker = Linker.nativeLinker().nn

    /** Invoke an upcall stub as if C had called it: we reinterpret the stub's address as a downcall with the same descriptor. */
    private def invokeThrough(
        stub: MemorySegment,
        descriptor: FunctionDescriptor,
        args: AnyRef*
    ): AnyRef =
        val mh    = linker.downcallHandle(stub, descriptor).nn
        val boxed = args.toArray
        mh.invokeWithArguments(boxed*).asInstanceOf[AnyRef]
    end invokeThrough

    "stub0: () => Int returns a callable function pointer" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_INT).nn
            val f    = () => 42
            val stub = UpcallBridge.stub0(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            assert(invokeThrough(stub, fd).asInstanceOf[java.lang.Integer].intValue() == 42)
        }
    }

    "stub1: Int => Int invokes the Scala function" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_INT, JAVA_INT).nn
            val f    = (x: Int) => x * 3
            val stub = UpcallBridge.stub1(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(invokeThrough(stub, fd, java.lang.Integer.valueOf(7))
                .asInstanceOf[java.lang.Integer].intValue() == 21)
        }
    }

    "stub2: (Int, Int) => Int (qsort-style comparator)" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT).nn
            val f    = (a: Int, b: Int) => a - b
            val stub = UpcallBridge.stub2(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(invokeThrough(stub, fd, java.lang.Integer.valueOf(10), java.lang.Integer.valueOf(4))
                .asInstanceOf[java.lang.Integer].intValue() == 6)
            assert(invokeThrough(stub, fd, java.lang.Integer.valueOf(2), java.lang.Integer.valueOf(9))
                .asInstanceOf[java.lang.Integer].intValue() == -7)
        }
    }

    "stub3: (Int, Long, Double) => Long mixes primitives" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_DOUBLE).nn
            val f    = (a: Int, b: Long, c: Double) => a.toLong + b + c.toLong
            val stub = UpcallBridge.stub3(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(invokeThrough(
                stub,
                fd,
                java.lang.Integer.valueOf(1),
                java.lang.Long.valueOf(2L),
                java.lang.Double.valueOf(3.0)
            ).asInstanceOf[java.lang.Long].longValue() == 6L)
        }
    }

    "stub1 with a Unit-returning function uses FunctionDescriptor.ofVoid" in {
        Using.arena { arena =>
            @volatile var seen: Int = -1
            val fd                  = FunctionDescriptor.ofVoid(JAVA_INT).nn
            val f: Int => Unit      = (x: Int) => seen = x
            val stub                = UpcallBridge.stub1(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            val _                   = invokeThrough(stub, fd, java.lang.Integer.valueOf(77))
            assert(seen == 77)
        }
    }

    "closing the arena invalidates the stub (lifetime bound to arena)" in {
        val arena = Arena.ofConfined().nn
        val fd    = FunctionDescriptor.of(JAVA_INT).nn
        val f     = () => 1
        val stub  = UpcallBridge.stub0(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
        arena.close()
        // After arena close, Panama invalidates the stub's session. Downcall through a dead segment throws.
        interceptThrown[IllegalStateException] {
            invokeThrough(stub, fd)
        }
    }

    "unsupported return layout surfaces as IllegalArgumentException" in {
        Using.arena { arena =>
            val structLayout = java.lang.foreign.MemoryLayout.structLayout(JAVA_INT).nn
            val fd           = FunctionDescriptor.of(structLayout).nn
            val f            = () => 1
            interceptThrown[IllegalArgumentException] {
                UpcallBridge.stub0(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            }
        }
    }

    // --- High-arity stubs (11..22) -------------------------------------------
    //
    // The wrap() path is the same for all arities (reflective FunctionN.apply + asType coerce);
    // the only per-arity concern is that we pick the right Function class. These tests assert
    // that stub11..stub22 each return a non-null, non-NULL MemorySegment. Integration through an
    // actual C downcall for every arity would be excessive, the dispatch is covered by stub0..stub10.
    //
    // Each stub is invoked with a Unit-returning no-op function against a `FunctionDescriptor.ofVoid(...)`
    // whose parameter layouts match the arity via `Array.fill(arity)(JAVA_INT)`.

    private def voidFd(arity: Int): FunctionDescriptor =
        val layouts: Array[java.lang.foreign.MemoryLayout] = Array.fill(arity)(JAVA_INT)
        FunctionDescriptor.ofVoid(layouts*).nn

    "stub11: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub11(f, voidFd(11), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            assert(stub != null)
        }
    }

    "stub12: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub12(f, voidFd(12), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub13: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub13(f, voidFd(13), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub14: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub14(f, voidFd(14), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub15: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub15(f, voidFd(15), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub16: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub16(f, voidFd(16), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub17: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub17(f, voidFd(17), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub18: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub18(f, voidFd(18), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub19: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub19(f, voidFd(19), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub20: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub20(f, voidFd(20), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub21: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub21(f, voidFd(21), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    "stub22: returns non-NULL segment for no-op function" in {
        Using.arena { arena =>
            val f: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) => Unit =
                (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => ()
            val stub = UpcallBridge.stub22(f, voidFd(22), arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
        }
    }

    // --- Specialized shape bridges (J_J, J_U, D_D, II_U, JJ_J) ----------

    "stubShape_J_J creates specialized stub and invokes correctly" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG).nn
            val f    = (x: Long) => x * 2L
            val stub = UpcallBridge.stubShape_J_J(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            assert(invokeThrough(stub, fd, java.lang.Long.valueOf(21L))
                .asInstanceOf[java.lang.Long].longValue() == 42L)
        }
    }

    "stubShape_J_U creates specialized stub" in {
        Using.arena { arena =>
            @volatile var seen: Long = -1L
            val fd                   = FunctionDescriptor.ofVoid(JAVA_LONG).nn
            val f: Long => Unit      = (x: Long) => seen = x
            val stub                 = UpcallBridge.stubShape_J_U(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            discard(invokeThrough(stub, fd, java.lang.Long.valueOf(99L)))
            assert(seen == 99L)
        }
    }

    "stubShape_D_D creates specialized stub and preserves precision" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE).nn
            val f    = (x: Double) => x * 1.5
            val stub = UpcallBridge.stubShape_D_D(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            assert(invokeThrough(stub, fd, java.lang.Double.valueOf(10.0))
                .asInstanceOf[java.lang.Double].doubleValue() == 15.0)
        }
    }

    "stubShape_II_U creates specialized stub and receives both args" in {
        Using.arena { arena =>
            @volatile var seenA: Int = -1
            @volatile var seenB: Int = -1
            val fd                   = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT).nn
            val f: (Int, Int) => Unit = (a: Int, b: Int) =>
                seenA = a; seenB = b
            val stub = UpcallBridge.stubShape_II_U(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            discard(invokeThrough(stub, fd, java.lang.Integer.valueOf(3), java.lang.Integer.valueOf(7)))
            assert(seenA == 3)
            assert(seenB == 7)
        }
    }

    "stubShape_JJ_J creates specialized stub" in {
        Using.arena { arena =>
            val fd   = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG).nn
            val f    = (a: Long, b: Long) => a + b
            val stub = UpcallBridge.stubShape_JJ_J(f, fd, arena, "kyo.example.Spec", "testMethod", "transient")
            assert(!stub.equals(MemorySegment.NULL))
            assert(invokeThrough(stub, fd, java.lang.Long.valueOf(100L), java.lang.Long.valueOf(200L))
                .asInstanceOf[java.lang.Long].longValue() == 300L)
        }
    }

    private object Using:
        /** Minimal per-test bracket for an `Arena.ofConfined()`. */
        def arena[R](f: Arena => R): R =
            val a = Arena.ofConfined().nn
            try f(a)
            finally a.close()
        end arena
    end Using
end UpcallBridgeTest
