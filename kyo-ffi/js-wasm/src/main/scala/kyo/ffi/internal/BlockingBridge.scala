package kyo.ffi.internal

import kyo.*
import scala.scalajs.js

/** Runtime bridge that lifts an asynchronous `@Ffi.blocking` koffi downcall into a `Fiber.Unsafe`.
  *
  * On JS the blocking C call cannot run on the carrier (the single Node event-loop thread): instead koffi dispatches it on a libuv worker
  * thread via `KoffiFacade.callAsync`, so the event loop is not blocked, and resolves the returned fiber from the completion callback. The
  * koffi `errno()` read inside that callback returns the errno of the async call (errno survives the async boundary), so the `marshal`
  * lambda supplied by the generated impl decodes the raw result and reads `KoffiFacade.errno()` exactly as the synchronous path does.
  *
  * The consumer bridges to `< Async` with `.safe.get`; that is outside this bridge's scope.
  */
object BlockingBridge:

    /** Dispatch the `@Ffi.blocking` koffi downcall on a libuv worker and return a fiber resolved from the completion callback.
      *
      * @param facade
      *   the per-trait koffi dispatch bag.
      * @param name
      *   the function key under which the koffi handle is stored in the bag.
      * @param args
      *   the marshalled argument array, identical to the synchronous call site's arg list.
      * @param marshal
      *   decodes the raw koffi result (and reads `KoffiFacade.errno()`) into the binding's return value. Invoked inside the completion
      *   callback on success.
      */
    def runAsync[A](facade: js.Dynamic, name: String, args: js.Array[js.Any], marshal: js.Any => A)(using
        AllowUnsafe
    ): Fiber.Unsafe[A, Any] =
        val p = Promise.Unsafe.init[A, Any]()
        KoffiFacade.callAsync(
            facade,
            name,
            args,
            (err, raw) =>
                if err != null && !js.isUndefined(err) then p.completeDiscard(Result.panic(js.JavaScriptException(err)))
                else
                    // marshal can throw (e.g. a non-nullable Handle return that decodes to NULL throws
                    // FfiNullPointer). On JVM/Native that throw propagates synchronously out of the carrier
                    // call and is caught by the surrounding < Async evaluation; on JS the marshal runs inside
                    // this libuv completion callback, so a thrown exception would escape as an uncaught error
                    // and terminate the Node process, leaving the promise forever incomplete. Capture it into
                    // the fiber result (Result.apply -> Panic on throw) so .safe.get surfaces it as a failure
                    // the consumer can catch, matching the JVM/Native synchronous-throw outcome.
                    p.completeDiscard(Result(marshal(raw)))
        )
        p
    end runAsync

end BlockingBridge
