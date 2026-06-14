package sdl2demo

import kyo.AllowUnsafe.embrace.danger
import kyo.ffi.*

/** Driver: initialize SDL, open a window, pump events via a Scala event handler,
  * clean up.
  */
object Main:
    // Mirror the stub's event-type constants.
    private final val EventKeydown = 0x300
    private final val EventKeyup   = 0x301
    private final val EventQuit    = 0x100

    def main(args: Array[String]): Unit =
        val sdl = Ffi.load[Sdl2Bindings]

        if sdl.init(0) != 0 then
            throw new AssertionError("SDL init failed")

        val window = sdl.createWindow(640, 480)
        if window == 0 then
            throw new AssertionError("createWindow returned null")

        // Callback-driven event pump. We count observed events and break on QUIT.
        var keydowns = 0
        var keyups   = 0
        var sawQuit  = false

        val processed = sdl.runEvents { (eventType, code) =>
            eventType match
                case EventKeydown =>
                    keydowns += 1
                    0
                case EventKeyup =>
                    keyups += 1
                    0
                case EventQuit =>
                    sawQuit = true
                    1 // non-zero -> stop the pump
                case _ =>
                    0
        }

        if keydowns != 3 then throw new AssertionError(s"expected 3 keydowns, got $keydowns")
        if keyups != 3 then throw new AssertionError(s"expected 3 keyups, got $keyups")
        if !sawQuit then throw new AssertionError("expected QUIT event")
        if processed != 7 then throw new AssertionError(s"expected processed=7, got $processed")

        sdl.destroyWindow(window)
        val _ = sdl.quit(0)

        println(s"OK: window=0x${window.toHexString} keydowns=$keydowns keyups=$keyups quit=$sawQuit")
    end main
end Main
