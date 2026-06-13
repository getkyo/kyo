package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** libm binding surface, `sqrt`, `pow`, `sin`, `cos`, `floor`, `fabs`.
  *
  * On Linux, libm is a separate shared library (`libm.so.6`). The codegen will emit `@link("m")` on Native and a `dlopen("libm.so.6")`
  * resolution cascade on JVM (via the NativeLoader's `mapLibraryName` fallback). On macOS, libm is folded into libSystem,  * `dlopen("libm.dylib")` still resolves (it is a symlink-to-/usr/lib/libSystem in the dyld shared cache), so the same `library = "m"`
  * literal works portably.
  *
  * On Scala.js, koffi loads libraries by absolute path; `SystemLibraryInit` sets the `KYO_FFI_M_PATH` env var per platform before the first
  * `Ffi.load`.
  */
trait LibMBindings extends Ffi:
    def sqrt(x: Double)(using AllowUnsafe): Double
    def pow(base: Double, exp: Double)(using AllowUnsafe): Double
    def sin(x: Double)(using AllowUnsafe): Double
    def cos(x: Double)(using AllowUnsafe): Double
    def floor(x: Double)(using AllowUnsafe): Double
    def fabs(x: Double)(using AllowUnsafe): Double
end LibMBindings

object LibMBindings extends Ffi.Config(library = "m")
