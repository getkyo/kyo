package kyo.net.internal

import kyo.*
import kyo.net.Test
import kyo.net.internal.util.HandleId
import scala.scalajs.js as sjs

/** `JsIoDriver.awaitWritable` fails the promise (typed Closed) on a socket `error` or `close`, never spuriously succeeds.
  *
  * `awaitWritable` registers one-shot `drain`/`close`/`error` listeners and completes the caller's promise when one fires. The `drain` event is
  * the success signal (the socket is writable again); `close`/`error` mean the socket went away before it became writable, so the awaiting write
  * must fail with a typed Closed. A single `complete()` on ALL three would make a write parked on writability over a dying socket
  * report Done and then write into a destroyed socket. Splitting the success path (`drain`) from the failure path (`close`/`error`)
  * matches every other driver. This drives the events on a Node EventEmitter mock socket (the emitted event is the latch; no sleep) and
  * asserts the promise outcome. The failure leaves would report Done if a single handler completed the promise on all three events.
  */
class JsIoDriverAwaitWritableFailureTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A Node EventEmitter standing in for a Node socket: it has real `once`/`removeListener`/`emit`, plus the `destroyed` flag `awaitWritable`
      * reads. The driver registers its one-shot listeners on it; the test emits the event to drive the handler.
      */
    private def mockSocket(): sjs.Dynamic =
        val EventEmitter = sjs.Dynamic.global.require("events").EventEmitter
        val sock         = sjs.Dynamic.newInstance(EventEmitter)()
        sock.destroyed = false
        sock
    end mockSocket

    "awaitWritable fails the promise with Closed on a socket error" in {
        val driver  = JsIoDriver.init()
        val socket  = mockSocket()
        val handle  = new JsHandle(socket, HandleId.next(0))
        val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
        driver.awaitWritable(handle, promise)
        // Drive the Node "error" event: the registered errorFn must fail the promise, not succeed.
        discard(socket.emit("error", sjs.Dynamic.literal(message = "boom")))
        assert(promise.done(), "awaitWritable did not complete the promise on a socket error")
        assert(
            promise.poll() match
                case Present(Result.Failure(_: Closed)) => true
                case _                                  => false,
            s"awaitWritable on a socket error must fail with Closed, got ${promise.poll()}"
        )
    }

    "awaitWritable fails the promise with Closed on a socket close" in {
        val driver  = JsIoDriver.init()
        val socket  = mockSocket()
        val handle  = new JsHandle(socket, HandleId.next(0))
        val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
        driver.awaitWritable(handle, promise)
        discard(socket.emit("close"))
        assert(promise.done(), "awaitWritable did not complete the promise on a socket close")
        assert(
            promise.poll() match
                case Present(Result.Failure(_: Closed)) => true
                case _                                  => false,
            s"awaitWritable on a socket close must fail with Closed, got ${promise.poll()}"
        )
    }

    "awaitWritable still succeeds on drain (the success path is preserved)" in {
        val driver  = JsIoDriver.init()
        val socket  = mockSocket()
        val handle  = new JsHandle(socket, HandleId.next(0))
        val promise = Promise.Unsafe.init[Unit, Abort[Closed]]()
        driver.awaitWritable(handle, promise)
        discard(socket.emit("drain"))
        assert(promise.done(), "awaitWritable did not complete the promise on drain")
        assert(
            promise.poll() match
                case Present(Result.Success(_)) => true
                case _                          => false,
            s"awaitWritable on drain must succeed, got ${promise.poll()}"
        )
    }

end JsIoDriverAwaitWritableFailureTest
