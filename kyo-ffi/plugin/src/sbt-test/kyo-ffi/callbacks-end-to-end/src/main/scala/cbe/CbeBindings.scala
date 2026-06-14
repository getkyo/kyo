package cbe

import kyo.AllowUnsafe
import kyo.ffi.*

trait CbeBindings extends Ffi:
    // Transient callback, buffer param: qsort an int array in-place via a
    // Scala-supplied comparator. C does not retain the callback past the call.
    def cbeQsortDemo(arr: Buffer[Int], len: Int, cmp: (Int, Int) => Int)(using AllowUnsafe): Unit

    // Transient callback, no buffers: pure function pointer exercise.
    def cbeSumPairs(cb: (Int, Int) => Int, n: Int)(using AllowUnsafe): Int

    // Retained callback: presence of `Ffi.Guard` classifies the method as Retained.
    // The generated code reads the arena from the guard so the upcall stub outlives
    // this call. `cbeRegisterHandler` stashes the callback C-side; a separate
    // `cbeFireHandler` invokes it later and proves the stub is still live.
    def cbeRegisterHandler(cb: Int => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit

    // Plain downcall that fires the previously-registered handler with `arg`.
    def cbeFireHandler(arg: Int)(using AllowUnsafe): Unit
end CbeBindings

object CbeBindings extends Ffi.Config(library = "cbe_lib")
