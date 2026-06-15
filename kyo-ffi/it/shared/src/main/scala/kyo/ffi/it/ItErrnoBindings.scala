package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** Errno-capture exercise.
  *
  * `kyoItAlwaysFail` unconditionally sets `errno = EINVAL (22)` and returns `-1`. Declared as `Ffi.Outcome[Int]` so the caller can inspect
  * both the return value and the captured error code; the `Int` width makes the C `int` return read at `JAVA_INT`, so a negative `-1`
  * sign-extends into the packed `Long` rather than zero-extending to `4294967295`.
  *
  * `kyoItClearErrno` sets `errno = 0` and returns `1`. Declared as a plain `Int` return, since errno = 0, the auto-throw contract is
  * satisfied (no throw).
  *
  * Errno capture is always on for every generated impl (see JvmEmitter's `Linker.Option.captureCallState("errno")`, NativeEmitter's
  * `errno.errno` read, and JsEmitter's koffi `errno` wrapper). No annotation is required on the binding method.
  */
trait ItErrnoBindings extends Ffi:
    /** C-side `errno = EINVAL; return -1;`. Outcome return: user inspects `.errorCode` and `.value`. */
    def kyoItAlwaysFail()(using AllowUnsafe): Ffi.Outcome[Int]

    /** C-side `errno = 0; return 1;`. Plain return: errno = 0 means no throw. */
    def kyoItClearErrno()(using AllowUnsafe): Int
end ItErrnoBindings

object ItErrnoBindings extends Ffi.Config(library = "kyo_it_bundled")
