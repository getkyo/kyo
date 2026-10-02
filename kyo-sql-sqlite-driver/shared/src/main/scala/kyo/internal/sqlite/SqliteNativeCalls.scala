package kyo.internal.sqlite

import kyo.*
import kyo.ffi.Ffi

/** The process-wide count of SQLite calls currently in native code, every `@Ffi.blocking` binding call passing through [[run]], and of the
  * connection handles open to each file.
  *
  * On JS these calls share Node's libuv worker pool, four threads by default, with every other blocking call in the process, and a call
  * submitted behind a full pool waits without having started. The call count is what tells a slow open from an open queued behind other
  * calls. The handle counts are taken where the native calls complete rather than where a fiber reads their result, so a handle whose open
  * was abandoned mid-call still counts until something closes it.
  */
private[kyo] object SqliteNativeCalls:

    /** A native result that must be released, held for whichever side releases it: the caller it was delivered to, from a finalizer it
      * armed before making the call, or the call's own completion when the caller had stopped waiting.
      *
      * The completion deposits the result BEFORE it completes the caller's wait. Depositing after would leave a window, between the caller
      * resuming and the deposit, in which an interrupt finds nothing to release. Both sides [[take]], so exactly one of them releases it.
      */
    final class Held[A](release: A => Unit)(using AllowUnsafe):
        private val ref = AtomicRef.Unsafe.init(Maybe.empty[A])

        private[SqliteNativeCalls] def put(a: A): Unit = ref.set(Present(a))

        /** Takes the result for the caller to own, leaving nothing for [[release]]. */
        def take(): Maybe[A] = ref.getAndSet(Absent)

        /** Releases the result unless the caller already took it. */
        def releaseHeld(): Unit = take().foreach(release)
    end Held

    // Unsafe: process-wide counters, created when the object loads and only ever updated atomically.
    private val inFlightCount: AtomicInt.Unsafe                  = AtomicInt.Unsafe.init(using AllowUnsafe.embrace.danger)
    private val handleCounts: AtomicRef.Unsafe[Map[String, Int]] =
        AtomicRef.Unsafe.init(Map.empty[String, Int])(using AllowUnsafe.embrace.danger)

    /** How many SQLite calls are in native code, or queued for a worker, right now. */
    def inFlight(using AllowUnsafe): Int = inFlightCount.get()

    /** How many connection handles are open to `path` right now, including any whose open nobody is waiting for any more. */
    def handlesOpen(path: String)(using AllowUnsafe): Int = handleCounts.get().getOrElse(path, 0)

    /** Starts `call` and suspends until it completes, counting it in [[inFlight]] from submission to completion.
      *
      * The caller waits on a promise of its own rather than on the call's. Waiting on the call's lets an interrupt of the caller complete it
      * as interrupted, and the result the native call returns afterwards is then dropped with it. A call whose result must be released
      * goes through [[runHolding]] instead.
      */
    def run[A](call: AllowUnsafe ?=> Fiber.Unsafe[A, Any])(using Frame): A < Async =
        runHolding(call)(_ => Absent, Absent)

    /** [[run]], depositing the part of the result `owned` picks into `held` before the caller resumes, and releasing it there when the
      * caller is no longer waiting.
      */
    def runHolding[A, R](call: AllowUnsafe ?=> Fiber.Unsafe[A, Any])(owned: A => Maybe[R], held: Maybe[Held[R]])(using Frame): A < Async =
        Sync.Unsafe.defer {
            val waiter = Promise.Unsafe.init[A, Any]()
            start(call).onComplete {
                case Result.Success(result) =>
                    val a = result.eval
                    held.foreach(h => owned(a).foreach(h.put))
                    if !waiter.complete(Result.succeed(a)) then held.foreach(_.releaseHeld())
                case Result.Panic(t)   => waiter.completeDiscard(Result.panic(t))
                case Result.Failure(e) => waiter.completeDiscard(Result.panic(new IllegalStateException(s"SQLite call failed with $e")))
            }
            waiter
        }.map(_.safe.get)

    /** Starts `call` without waiting for it, counting it in [[inFlight]] until it completes. */
    def start[A](call: AllowUnsafe ?=> Fiber.Unsafe[A, Any])(using AllowUnsafe): Fiber.Unsafe[A, Any] =
        discard(inFlightCount.incrementAndGet())
        val fiber = call
        fiber.onComplete(_ => discard(inFlightCount.decrementAndGet()))
        fiber
    end start

    /** A [[Held]] for a connection handle to `path`, which releases it by closing it. */
    def heldHandle(bindings: SqliteBindings, path: String)(using AllowUnsafe): Held[Ffi.Handle[SqliteDb]] =
        Held(db => discard(startClose(bindings, db, path)))

    /** Opens `path`, counting the handle in [[handlesOpen]] the moment the native call returns one and depositing it into `held`. */
    def open(bindings: SqliteBindings, path: String, flags: Int, vfs: String, held: Held[Ffi.Handle[SqliteDb]])(using
        Frame
    ): Maybe[Ffi.Handle[SqliteDb]] < Async =
        // Unsafe: the count is taken in the native call's completion, which cannot suspend.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val counted       = (handle: Maybe[Ffi.Handle[SqliteDb]]) =>
            if handle.isDefined then adjust(path, 1)
            handle
        runHolding(bindings.openV2(path, flags, vfs).map(counted))(identity, Present(held))
    end open

    /** Starts closing `db`, an open handle to `path`, uncounting it once the native call completes. */
    def startClose(bindings: SqliteBindings, db: Ffi.Handle[SqliteDb], path: String)(using AllowUnsafe): Fiber.Unsafe[Int, Any] =
        val fiber = start(bindings.closeV2(db))
        fiber.onComplete(_ => adjust(path, -1))
        fiber
    end startClose

    private def adjust(path: String, delta: Int)(using AllowUnsafe): Unit =
        discard(handleCounts.updateAndGet { counts =>
            val next = counts.getOrElse(path, 0) + delta
            if next == 0 then counts - path else counts.updated(path, next)
        })

end SqliteNativeCalls
