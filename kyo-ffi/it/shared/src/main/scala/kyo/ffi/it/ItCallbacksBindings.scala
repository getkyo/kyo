package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Transient + retained callback exercises.
  *
  * Two callback lifetime categories, transient and retained, are exercised here. Transient callbacks are invoked synchronously during the
  * FFI call and not held past return; the generated impl allocates the upcall stub on a per-call confined scope, so no guard is required.
  * Retained callbacks are stashed in process-global state C-side and invoked by a later FFI call; their Scala-side upcall stubs must
  * outlive the register call, which is declared via an [[Ffi.Guard]] parameter that pins the stub for the guard's lifetime.
  *
  * Bindings live in `shared/src/main`, the codegen produces platform-specific impls for JVM (Panama upcalls), JS (koffi register +
  * `koffi.pointer(Proto)` for retained shapes) and Native (CFuncPtrN). The shared `ItCallbacksSpec` runs on every platform, JVM, Scala
  * Native, and Scala.js, exercising both transient (`kyoItSortInts`) and retained (`kyoItRegisterListener`/`kyoItFireListener`) flows.
  */
trait ItCallbacksBindings extends Ffi:
    /** In-place sort of `arr` using `cmp` as the three-way comparator. `cmp` is transient, the comparator is invoked synchronously during
      * the call and not retained past return. The C side uses `qsort` under the hood; the comparator is routed through a single-slot
      * process-global adaptor, so this is not reentrant, do not call from multiple threads concurrently.
      */
    def kyoItSortInts(arr: Buffer[Int], n: Int, cmp: (Int, Int) => Int)(using AllowUnsafe): Unit

    /** Register `cb` as the process-global listener. The upcall stub is tied to `guard`'s lifetime; invoking `kyoItFireListener` after
      * `guard.close()` is undefined behavior. Parameter order mirrors `CbeBindings.cbeRegisterHandler`, callback first, guard last.
      */
    def kyoItRegisterListener(cb: Int => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit

    /** Fire the previously-registered listener with `arg`. Plain downcall, does not re-arm the listener. No-op if `kyoItRegisterListener`
      * was never called.
      */
    def kyoItFireListener(arg: Int)(using AllowUnsafe): Unit
end ItCallbacksBindings

object ItCallbacksBindings extends Ffi.Config(library = "kyo_it_bundled")
