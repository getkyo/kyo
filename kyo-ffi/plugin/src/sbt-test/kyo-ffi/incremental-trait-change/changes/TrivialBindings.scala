package inc

import kyo.AllowUnsafe
import kyo.ffi.*

trait TrivialBindings extends Ffi:
    def incChange(a: Int)(using AllowUnsafe): Int
    // Added incrementally: the generated impl must regenerate from this new trait, not the
    // stale class-dir TASTy of the previous compile (#247). A stale impl omits incChange2 and
    // fails to compile ("needs to be abstract").
    def incChange2(a: Int)(using AllowUnsafe): Int
end TrivialBindings

object TrivialBindings extends Ffi.Config(library = "inc_change_lib")
