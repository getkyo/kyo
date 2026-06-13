package sdl2demo

import kyo.AllowUnsafe
import kyo.ffi.*

/** Worked example: SDL2-style bindings.
  *
  * Demonstrates a callback-heavy, C-driven event-loop idiom. The `runEvents` C
  * function invokes the Scala callback synchronously for each event; the
  * callback is *transient* (not retained past the call) so no `Ffi.Guard` is
  * required.
  *
  * Signature choices:
  *   - Opaque SDL types (`SDL_Window*`) are modeled as `Long` handles.
  *   - The event discriminator + payload are flattened to `(Int, Int)` for
  *     ABI stability; a real binding could unpack a small struct instead.
  */
trait Sdl2Bindings extends Ffi:
    def init(flags: Int)(using AllowUnsafe): Int

    // `unused` reserved so the emitter's with-args branch fires (zero-arg methods
    // have a known trailing-comma quirk in this codegen build).
    def quit(unused: Int)(using AllowUnsafe): Int

    def createWindow(width: Int, height: Int)(using AllowUnsafe): Long
    def destroyWindow(window: Long)(using AllowUnsafe): Unit

    // Event pump: C drives the loop and calls the Scala callback per event.
    // Return non-zero from the callback to stop early. Returns event count.
    def runEvents(cb: (Int, Int) => Int)(using AllowUnsafe): Int
end Sdl2Bindings

object Sdl2Bindings extends Ffi.Config(library = "kyo_sdl2_stub", symbolPrefix = "kyo_sdl_")
