package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** libc binding surface, `strlen`, `strcmp`, `memcmp`, `abs`, `labs`, `memcpy`.
  *
  * The codegen derives the C symbol as the snake_case of the Scala method name (already matches libc's names here), and reads the library
  * name from the companion's `Ffi.Config`. The library literal must be a compile-time string constant, the codegen rejects runtime
  * references.
  *
  * A bare "c" works on JVM (Foreign Linker's default `dlopen`) and Native (Scala Native auto-links libc). On Scala.js the loader resolves
  * it, as it does for any application, to the symbols already loaded into the Node process (koffi's process scope), which include libc on
  * every POSIX host, so no path is involved.
  */
trait LibCBindings extends Ffi:
    def strlen(s: String)(using AllowUnsafe): Long

    /** lexicographic comparison of two C strings, returns 0 on equal, < 0 if `a` < `b`, > 0 otherwise. */
    def strcmp(a: String, b: String)(using AllowUnsafe): Int

    /** compare `n` bytes of two memory buffers, 0 if equal, < 0 / > 0 per the first differing byte. */
    def memcmp(a: Buffer[Byte], b: Buffer[Byte], n: Long)(using AllowUnsafe): Int

    /** absolute value of an `int`. */
    def abs(x: Int)(using AllowUnsafe): Int

    /** absolute value of a `long` (C `long`, which is 64-bit on LP64 Unix systems, matches Scala `Long` on macOS/Linux). */
    def labs(x: Long)(using AllowUnsafe): Long

    /** copy `n` bytes from `src` into `dst`. Returns the destination (dropped here, we expose `Unit`). */
    def memcpy(dst: Buffer[Byte], src: Buffer[Byte], n: Long)(using AllowUnsafe): Unit
end LibCBindings

object LibCBindings extends Ffi.Config(library = "c")
