package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** Non-variadic three-int sum binding, cross-platform coverage for the workaround-pointer pattern the F8b codegen directs users to when a
  * binding needs C varargs but the target is Scala Native (whose `@extern` cannot express variadic functions). `kyoItSumFixed3` maps to the
  * bundled `kyo_it_sum_fixed_3(int a, int b, int c)` C helper.
  */
trait ItSumFixedBindings extends Ffi:
    def kyoItSumFixed3(a: Int, b: Int, c: Int)(using AllowUnsafe): Int
end ItSumFixedBindings

object ItSumFixedBindings extends Ffi.Config(library = "kyo_it_bundled")
