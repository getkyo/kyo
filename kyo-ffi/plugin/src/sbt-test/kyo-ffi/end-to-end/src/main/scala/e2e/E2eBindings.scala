package e2e

import kyo.AllowUnsafe
import kyo.ffi.*

trait E2eBindings extends Ffi:
    def e2eAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def e2eSub(a: Int, b: Int)(using AllowUnsafe): Int
    def e2eMulI64(a: Long, b: Long)(using AllowUnsafe): Long
end E2eBindings

object E2eBindings extends Ffi.Config(library = "e2e_lib")
