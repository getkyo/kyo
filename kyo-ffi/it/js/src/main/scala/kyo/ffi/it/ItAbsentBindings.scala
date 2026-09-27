package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** A binding whose library no host installs; its test points the library's path override at a file that is not a library, so loading it
  * exercises the failure path of `Ffi.load`.
  *
  * Not shared: Scala Native links a binding's library into the executable, where an absent one fails the link of the whole test binary.
  */
trait ItAbsentBindings extends Ffi:
    def kyoItAbsentFn()(using AllowUnsafe): Int
end ItAbsentBindings

object ItAbsentBindings extends Ffi.Config(library = "kyo_it_absent")
