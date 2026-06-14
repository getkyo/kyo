package je

import kyo.AllowUnsafe
import kyo.ffi.*

trait JeBindings extends Ffi:
    def jeAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def jeSub(a: Int, b: Int)(using AllowUnsafe): Int
end JeBindings

object JeBindings extends Ffi.Config(library = "je_lib")
