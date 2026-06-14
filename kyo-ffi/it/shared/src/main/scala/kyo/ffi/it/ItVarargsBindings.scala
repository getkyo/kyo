package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** Variadic (`Any*`) function IT surface (F8b #25), JVM + JS only at runtime.
  *
  * `kyoItSumVarargs(count, args*)` invokes the bundled C helper `kyo_it_sum_varargs(int count, ...)` which iterates `count` integers via
  * `va_arg` and returns their sum. Scala Native's `@extern` cannot express variadic function pointers, so this binding is intentionally not
  * loaded on Native; Native uses [[ItSumFixedBindings]] as the workaround-pointer substitute.
  *
  * The trait itself lives in the shared source set so that [[ItVarargsSharedSpec]] (also in shared/test) can reference the type without
  * platform-specific source duplication. The codegen correctly rejects any attempt to generate a Native impl, that rejection is what makes
  * varargs an intentionally JVM+JS-only feature.
  */
trait ItVarargsBindings extends Ffi:
    def kyoItSumVarargs(count: Int, args: Any*)(using AllowUnsafe): Int
end ItVarargsBindings

object ItVarargsBindings extends Ffi.Config(library = "kyo_it_bundled")
