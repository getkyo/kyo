package kyo.net.internal.transport

import kyo.*

/** Pump that drains an outbound channel and writes bytes to the socket via the driver.
  *
  * #### Lifecycle
  *
  *   1. `start()` registers a fresh taker on the outbound channel
  *   2. Channel delivers a span, `onTake` fires
  *   3. Pump CASes Idle -> Flushing and calls `driver.write(data)`
  *   4. If Done: CAS Flushing -> Idle, register fresh taker for next span
  *   5. If Partial: CAS Flushing -> AwaitingWritable, register fresh writable promise, retry remaining bytes when socket writable
  *   5. If TailPartial: CAS Flushing -> Backpressured, register fresh promise, retry when drain releases the tail-bound waiter
  *   6. If Error: teardown
  *
  * #### State machine
  *
  * The pump holds a named [[WriteState]] in one atomic cell, advanced by single-CAS transitions. A
  * stale completion (a take or writable that fires after the state already advanced) LOSES the CAS
  * and no-ops, so the no-op-on-stale property is structural rather than an unasserted assumption.
  *
  * #### Fresh promise per operation
  *
  * Each take and each writable await uses a fresh promise. The pump is not its own reused channel
  * taker (no `selfForTake = this` cast). The reused-promise double-fire race (a stale writable
  * firing on a reused promise after its pending bytes were cleared) is unrepresentable: there is no
  * separate `pending` field to clear, only a state to CAS.
  *
  * #### CAS identity
  *
  * [[AtomicRef.Unsafe.compareAndSet]] delegates to [[java.util.concurrent.atomic.AtomicReference]]
  * which uses reference equality. Every CAS therefore passes the STORED state instance as the
  * expected value, obtained via [[AtomicRef.Unsafe.get]] immediately before the CAS. `doWrite`
  * receives the exact [[WriteState.Flushing]] instance stored in the cell rather than constructing
  * a new one.
  */
final private[kyo] class WritePump[Handle](
    handle: Handle,
    driver: IoDriver[Handle],
    channel: Channel.Unsafe[Span[Byte]],
    closeFn: () => Unit,
    private val state: AtomicRef.Unsafe[WriteState],
    private val log: Log.Unsafe = Log.live.unsafe
):
    // The pump is not its own channel taker and does not reuse one IOPromise. Each take and each
    // writable await uses a FRESH promise whose completion callback CASes the named WriteState; a
    // stale completion (a take that fired after the state already advanced) LOSES the CAS and
    // observes the winner, so a stale completer is a structural no-op rather than depending on any
    // "no-waiters-so-flush-is-a-no-op" assumption.

    /** Start the pump. Registers a fresh taker on the channel for the first span. */
    def start()(using AllowUnsafe, Frame): Unit =
        log.info(s"WritePump starting on ${driver.handleLabel(handle)}")
        requestNextTake()

    /** Take callback: a span arrived (or the channel closed). Idle -> Flushing CAS, then write. */
    private def onTake(result: Result[Closed, Span[Byte]])(using AllowUnsafe, Frame): Unit =
        result match
            case Result.Success(bytes) =>
                // No per-take log on the steady write path: the abstract Log.Unsafe materializes a Function0 per call even when the
                // level gates the string. The success-path ReadPump has no per-op log either.
                // Create the Flushing instance once and pass the SAME reference to doWrite for the CAS.
                val flushing: WriteState.Flushing = WriteState.Flushing(bytes, 0)
                if state.compareAndSet(WriteState.Idle, flushing) then
                    doWrite(flushing)
                // else: state is TornDown (a concurrent close won); drop the span, teardown already ran.
            case Result.Failure(_: Closed) =>
                teardown()
            case Result.Panic(t) =>
                log.error(s"WritePump take panic on ${driver.handleLabel(handle)}", t)
                teardown()
        end match
    end onTake

    /** Writable callback: the socket became writable, or the await failed. Resume the parked tail. */
    private def onWritable(result: Result[Closed, Unit])(using AllowUnsafe, Frame): Unit =
        result match
            case Result.Success(_) =>
                // Resume the tail recorded in AwaitingWritable/Backpressured. The CAS to Flushing is
                // the single winner: a stale writable that fired after the state already advanced
                // loses it and no-ops (the structural no-op-on-stale, not an unasserted assumption).
                // The stored state instance `s` is passed to the CAS as the expected value (reference equality).
                state.get() match
                    case s @ WriteState.AwaitingWritable(pending, off) =>
                        val flushing: WriteState.Flushing = WriteState.Flushing(pending, off)
                        if state.compareAndSet(s, flushing) then doWrite(flushing)
                    case s @ WriteState.Backpressured(pending, off) =>
                        val flushing: WriteState.Flushing = WriteState.Flushing(pending, off)
                        if state.compareAndSet(s, flushing) then doWrite(flushing)
                    case _ => () // Idle/Flushing/TornDown: a stale writable, drop it.
                end match
            case Result.Failure(_) | Result.Panic(_) =>
                teardown()
        end match
    end onWritable

    // `current` is the STORED WriteState.Flushing instance. It must be passed through to every CAS
    // so that AtomicReference.compareAndSet (which uses reference equality) finds the right object.
    // TODO it seems this method can discard the flushing bytes in mutliple paths?
    private def doWrite(current: WriteState.Flushing)(using AllowUnsafe, Frame): Unit =
        driver.write(handle, current.pending, current.offset) match
            case WriteResult.Done =>
                // Flushing -> Idle, then take the next span. The CAS from the stored `current` instance
                // is the single winner; a racing teardown that swung TornDown makes this lose and stop.
                if state.compareAndSet(current, WriteState.Idle) then requestNextTake()
            case WriteResult.Partial(remaining, newOffset) =>
                // Park on socket writability with a FRESH promise (no reused promise). Flushing -> AwaitingWritable
                // carries the tail inline; the fresh promise's completion runs onWritable.
                val next = WriteState.AwaitingWritable(remaining, newOffset)
                if state.compareAndSet(current, next) then
                    val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    p.onComplete { r =>
                        import AllowUnsafe.embrace.danger
                        given Frame = Frame.internal
                        onWritable(r.asInstanceOf[Result[Closed, Unit]])
                    }
                    driver.awaitWritable(handle, p)
                end if
            case WriteResult.TailPartial(remaining, newOffset) =>
                // Park on the write-tail backpressure bound with a FRESH promise. Flushing -> Backpressured
                // carries the tail inline; the fresh promise is stored on the handle by awaitWritable and
                // completed by the drain path (releaseBackpressureWaiter) once the tail falls below the
                // low-water mark. The promise's completion runs onWritable, which CASes Backpressured ->
                // Flushing. A stale completer (e.g. a second fire of the drain callback) loses the CAS and
                // no-ops.
                val next = WriteState.Backpressured(remaining, newOffset)
                if state.compareAndSet(current, next) then
                    val p = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    p.onComplete { r =>
                        import AllowUnsafe.embrace.danger
                        given Frame = Frame.internal
                        onWritable(r.asInstanceOf[Result[Closed, Unit]])
                    }
                    driver.awaitWritable(handle, p)
                end if
            case WriteResult.Error =>
                log.debug(s"WritePump write error on ${driver.handleLabel(handle)}")
                teardown()
        end match
    end doWrite

    private def requestNextTake()(using AllowUnsafe, Frame): Unit =
        // A FRESH take promise per span: the pump registers a new taker per take, never a reused one.
        val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
        p.onComplete { r =>
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            onTake(r.asInstanceOf[Result[Closed, Span[Byte]]]) // TODO why do we need this cast? review all casts of the module
        }
        channel.reuseTake(p)
    end requestNextTake

    private def teardown()(using AllowUnsafe, Frame): Unit =
        // Swing the state to TornDown so a racing take/writable completion loses its CAS and no-ops.
        state.set(WriteState.TornDown)
        discard(channel.close())
        closeFn()
    end teardown

end WritePump
