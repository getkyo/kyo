package kyo.net.internal

import kyo.*
import kyo.net.NetConnectionClosedException
import kyo.net.NetDriverUnsupportedException
import kyo.net.NetException
import kyo.net.internal.transport.*
import kyo.scheduler.IOPromise
import scala.scalajs.js

/** JS I/O driver backed by Node.js socket events.
  *
  * Unlike the NIO and Native drivers, there is no poll loop: Node.js's own event loop dispatches I/O events. Read readiness arrives as a
  * `"data"` event, and write backpressure is signalled by the `"drain"` event. The `start()` method returns a sentinel `IOPromise` that
  * completes only when `close()` is called, satisfying the `IoDriver` contract without spinning a background fiber.
  *
  * Data flow: each Node.js socket is paused after creation; `awaitRead` calls `socket.resume()` to request the next chunk. When a chunk
  * arrives that is larger than what the caller expects, the excess bytes are stored as `leftover` in the `JsHandle` and delivered on the
  * next `awaitRead` without touching the socket.
  *
  * Note: there is no per-handle read buffer because Node.js delivers data through its own `Buffer` objects; only a shallow copy (Uint8Array
  * to Array[Byte]) is needed.
  */
final private[kyo] class JsIoDriver private (
    private val shutdownPromise: IOPromise[Any, Unit],
    private val closedFlag: AtomicBoolean.Unsafe
) extends IoDriver[JsHandle]:

    // Diagnostics state. Node's event loop has no poll cycle to count, so progress is the number of reads and writable waits armed: a pump
    // arms its next one only after the previous completed, so the count stands still exactly when no I/O completes. Every access runs on
    // the single event-loop thread.
    private var armedOps: Long                                                 = 0L
    private var pendingWritables: Int                                          = 0
    private val readArmed: scala.collection.mutable.Set[JsHandle]              = scala.collection.mutable.Set.empty
    private var diagRegistration: kyo.internal.Diagnostics.Registration | Null = null

    def label: String = "JsIoDriver"

    def handleLabel(handle: JsHandle): String = s"socket#${handle.id}"

    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
        // Monitor for unexpected completion
        shutdownPromise.onComplete { result =>
            result match
                case Result.Success(_) =>
                    if closedFlag.get() then
                        Log.live.unsafe.info(s"$label sentinel completed after close()")
                case Result.Failure(e) =>
                    Log.live.unsafe.error(s"$label sentinel failed: $e")
                case Result.Panic(t) =>
                    Log.live.unsafe.error(s"$label sentinel crashed", t)
        }

        // Same naming as PollerIoDriver's registration, including the marker the stranded-op gate exempts for a process-lifetime transport.
        val diagName =
            "JsIoDriver@" + java.lang.System.identityHashCode(this) +
                (if ProcessSharedTransport.isBuilding then " processSharedTransport" else "")
        diagRegistration = kyo.internal.Diagnostics.register(diagName)(
            dump = () =>
                val reads = new StringBuilder
                pendingReadHandles().foreach(h => discard(reads.append(handleLabel(h)).append(' ')))
                s"closed=${closedFlag.get()} armedOps=$armedOps pendingWritables=$pendingWritables pendingReads=[$reads]"
            ,
            probe = () =>
                kyo.internal.Diagnostics.Probe(
                    closed = closedFlag.get(),
                    cycles = armedOps,
                    pending = pendingWritables > 0 || pendingReadHandles().nonEmpty
                )
        )

        // Fiber.Unsafe[A, S] is an opaque alias over IOPromiseBase[Any, A < (Async & S)] (kyo.Fiber.scala), structurally different from this
        // plainly-constructed IOPromise[Any, Unit], even though both erase to the same runtime object; the alias is transparent only inside
        // kyo.Fiber's own defining scope, so exposing shutdownPromise as the locked IoDriver.start return needs this erased-boundary cast.
        // Safe: the promise completes only when close() settles it with Unit above.
        shutdownPromise.asInstanceOf[Fiber.Unsafe[Unit, Any]]
    end start

    def awaitRead(handle: JsHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
        armedOps += 1
        // Node's net.Socket#destroyed / #readableEnded are documented boolean properties; js.Dynamic erases them to untyped JS values, so recovering
        // the typed Boolean needs these narrowing casts. Safe per Node's documented property types.
        if !handle.life.armable then promise.completeDiscard(Result.fail(released(handle)))
        else if handle.hasLeftover then
            // (i) Deliver any staged/leftover chunk FIRST, in order, even if the socket has since ended/been destroyed: a peer-close-probe-induced
            // 'end' (and Node's allowHalfOpen=false auto-destroy) must not drop bytes the probe already staged.
            deliverLeftover(handle, promise)
        else if handle.socket.readableEnded.asInstanceOf[Boolean] then
            // (ii) The readable side ended (peer FIN, buffer fully consumed). Surface EOF. MANDATORY after a probe-induced 'end' fired with no pending
            // read (signalEof dropped it): a resume() on an already-ended stream would park forever, since no further 'data'/'end' will come.
            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        else if handle.socket.destroyed.asInstanceOf[Boolean] then
            // (iii) Socket destroyed (RST, or a completed close) with nothing staged: fail Closed.
            promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "socket destroyed")))
        else if handle.life.read.park(promise, released(handle)) then
            // (iv) Request the next chunk: the permanent 'data' listener delivers it (or 'end'/'error' the EOF/failure).
            readArmed += handle
            discard(handle.socket.resume())
        end if
    end awaitRead

    /** The failure of an op parked after the handle's release claim. */
    private def released(handle: JsHandle)(using Frame): Closed =
        Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "released")

    /** Whether the peer has closed, for the ReadPump's grace poll (poll-on-expiry). Node gives no non-consuming FIN signal on a PAUSED socket:
      * `pause()` calls `readStop`, so while backpressured libuv does not read the kernel socket and a FIN/RST never reaches Node (`readableEnded`/
      * `destroyed` stay false forever). Consuming inline is fine: the kick runs on the same single event-loop carrier as every socket callback
      * (unlike NIO). `false` here can mean "not observed yet". Skipped once staged bytes hit the cap or when the 'data' listener is gone (detached for a STARTTLS upgrade).
      */
    override def isPeerClosed(handle: JsHandle)(using AllowUnsafe, Frame): Boolean =
        val s = handle.socket
        if s.destroyed.asInstanceOf[Boolean] || s.readableEnded.asInstanceOf[Boolean] then true
        else if handle.stagedBytes >= JsIoDriver.PeerProbeBufferCap then false
        else if s.listenerCount("data").asInstanceOf[Int] == 0 then false
        else
            // Resume for one chunk: the permanent 'data' listener re-pauses and stashes it, or 'end'/'error' latches the close.
            discard(s.resume())
            false
        end if
    end isPeerClosed

    /** STARTTLS handoff: the plaintext ReadPump pulled `bytes` off the socket but detachForUpgrade already closed the inbound
      * channel, so these are the peer's first TLS flight (the ClientHello a server pulled a moment before detaching).
      * The [[kyo.net.internal.transport.IoDriver]] default drops them, which strands the handshake at its deadline.
      * Guarded on `upgrading` so an ordinary teardown close still discards. Single event-loop
      * carrier, so the plain enqueue is safe; the read routes to EITHER the channel (offer succeeds) OR here (offer fails Closed), never both, so
      * no bytes are fed twice.
      */
    override def onInboundClosedDuringRead(handle: JsHandle, bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        if handle.upgrading then
            val arr = bytes.toArrayUnsafe
            handle.enqueueLeftover(arr, 0, arr.length)
    end onInboundClosedDuringRead

    def awaitConnect(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // JS connect is handled via Node.js 'connect' event callback, not via the driver
        promise.completeDiscard(Result.succeed(()))

    def awaitAccept(handle: JsHandle, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // JS does not run the PosixTransport accept loop; fail fast so a caller that accidentally uses this path gets an immediate error.
        promise.completeDiscard(Result.Panic(NetDriverUnsupportedException(label, "awaitAccept")))

    def awaitWritable(handle: JsHandle, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
        // Node's net.Socket#destroyed is a documented boolean property; js.Dynamic erases that to an untyped JS value, so recovering the typed
        // Boolean needs this narrowing cast. Safe per Node's documented property type; it cannot dissolve without a typed facade for Node's
        // net.Socket.
        if handle.socket.destroyed.asInstanceOf[Boolean] then
            promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "socket destroyed")))
        else if handle.life.writable.park(promise, released(handle)) then
            // The `drain` event is the success signal (the socket is writable again); `close`/`error` mean the socket went away before it became
            // writable, so the awaiting write must fail with a typed Closed, never spuriously succeed. A single `complete()` shared by all three
            // listeners would report Done for a write parked over a dying socket and then write into a destroyed socket, so success and failure
            // have separate completions here. This matches every other driver, where a close/error on a pending op fails the promise with Closed.
            val drainFn: js.Function0[Unit] = () =>
                discard(handle.life.writable.clearIf(promise))
                promise.completeDiscard(Result.succeed(()))
            def failFn(reason: String): Unit =
                discard(handle.life.writable.clearIf(promise))
                promise.completeDiscard(Result.fail(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, reason)))
            val closeFn: js.Function0[Unit]             = () => failFn("socket closed before writable")
            val errorFn: js.Function1[js.Dynamic, Unit] = (_: js.Dynamic) => failFn("socket error before writable")

            armedOps += 1
            pendingWritables += 1
            discard(handle.socket.once("drain", drainFn))
            discard(handle.socket.once("close", closeFn))
            discard(handle.socket.once("error", errorFn))
            // However the wait settles (drain, close, error, or a cancel or release failing it in the slot), its listeners come off the socket,
            // so a cancelled wait leaves nothing behind for a later event to fire.
            promise.onComplete { _ =>
                pendingWritables -= 1
                discard(handle.socket.removeListener("drain", drainFn))
                discard(handle.socket.removeListener("close", closeFn))
                discard(handle.socket.removeListener("error", errorFn))
            }
        end if
    end awaitWritable

    def life(handle: JsHandle): HandleLife = handle.life

    def write(handle: JsHandle, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
        given Frame = Frame.internal
        // Two Node narrowing casts below: net.Socket#destroyed (a documented boolean property) and net.Socket#write (a documented boolean
        // return, where false signals backpressure). js.Dynamic erases both to untyped JS values, so recovering the typed Boolean needs these
        // narrowing casts. Safe per Node's documented types; they cannot dissolve without a typed facade for Node's net.Socket.
        val ledger = handle.life.ledger
        ledger.failure match
            case Present(cause) => WriteResult.Error(cause)
            case Absent         =>
                if data.isEmpty || offset >= data.size then WriteResult.Done
                else if !handle.life.armable then
                    WriteResult.Error(NetConnectionClosedException(
                        NetConnectionClosedException.Operation.Send,
                        "the connection was released"
                    ))
                else if handle.socket.destroyed.asInstanceOf[Boolean] then
                    WriteResult.Error(NetConnectionClosedException(NetConnectionClosedException.Operation.Send, "socket destroyed"))
                else
                    // Node.js socket.write accepts the data slice starting at offset. On backpressure (flushed == false), the data was already
                    // handed to Node.js, so we must NOT re-send it on the next pump call. Returning Partial(data, data.size) causes the pump to
                    // retry with offset == data.size, which computes len == 0 and returns Done -- effectively "wait for drain, then proceed".
                    // Node owns the bytes from here; the write's callback, which libuv runs once they are written out, is the only public
                    // signal that the kernel took them.
                    val n = data.size - offset
                    ledger.accept(n)
                    ledger.encode(n, n)
                    val written: js.Function1[js.Dynamic, Unit] = (err: js.Dynamic) =>
                        if js.isUndefined(err) || err == null then ledger.send(n)
                        else
                            val cause = if js.typeOf(err.message) == "string" then err.message.toString else "write failed"
                            discard(ledger.fail(NetConnectionClosedException(NetConnectionClosedException.Operation.Send, cause)))
                    val flushed = handle.socket.write(toNodeBuffer(data, offset), written).asInstanceOf[Boolean]
                    if flushed then WriteResult.Done
                    else WriteResult.Partial(data, data.size) // accepted, not flushed: sentinel offset prevents double-send on retry
                end if
        end match
    end write

    // Node's sockets are objects, never fd numbers this driver keys anything by, so a recycled number cannot reach a closed handle's state.
    def releaseFd(handle: JsHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()

    // Single threaded: every Node callback already runs on the one event loop.
    def runOwned(handle: JsHandle)(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()

    def closeListener(handle: JsHandle, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
        discard(handle.life.claimRelease())
        try cancel(handle)
        finally releaseFd(handle, closeFd)
    end closeListener

    /** Fails every op parked on the handle, the read and a writable wait on `drain` alike, and pauses the socket. Takes no release claim. */
    def cancel(handle: JsHandle)(using AllowUnsafe, Frame): Unit =
        discard(handle.socket.pause())
        handle.life.cancel(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "canceled"))
    end cancel

    /** The handle's one release. `released` completes on Node's `close`, once the socket's handle is closed.
      *
      * A graceful release ends the writable side and destroys the socket once Node reports it finished, instead of destroying at once.
      * `destroy()` discards what the writable side still holds, and `end()` also sends a TLS socket's close_notify before the FIN; `finish`
      * is Node's signal that every accepted write reached the underlying socket, and the destroy follows it. The wait is bounded by the
      * handle's `closeFlushGrace`: a peer that never drains never lets the output finish, and the socket is destroyed regardless when the
      * window ends. `Duration.Infinity` (stdio, whose shim owns no socket) arms no timer.
      *
      * An abortive release resets the connection instead, so the peer cannot read the stream it lost the end of as complete. Node resets only
      * a TCP handle (`resetAndDestroy` on a `tls.TLSSocket` throws `ERR_INVALID_HANDLE_TYPE`), so a TLS socket's raw socket is reset, which
      * closes the TLS socket too. Every TLS socket kyo holds wraps one: the accepted and upgraded ones by construction, and `connectTls` opens
      * its own. A socket Node already destroyed (the upgrade abandon, the handshake deadline, a peer reset) is left alone either way.
      */
    def release(handle: JsHandle, mode: ReleaseMode, released: Promise.Unsafe[Unit, Any])(using allow: AllowUnsafe, frame: Frame): Unit =
        if handle.life.claimRelease() then releaseClaimed(handle, mode, released)

    private def releaseClaimed(handle: JsHandle, mode: ReleaseMode, released: Promise.Unsafe[Unit, Any])(using
        allow: AllowUnsafe,
        frame: Frame
    ): Unit =
        // Node's net.Socket#destroyed and #writableFinished are documented boolean properties; js.Dynamic erases them to untyped JS values,
        // so recovering the typed Boolean needs these narrowing casts. Safe per Node's documented property types; they cannot dissolve
        // without a typed facade for Node's net.Socket.
        handle.life.cancel(Closed(s"connection ${handleLabel(handle)}", handle.createdAt, "closed"))
        readArmed -= handle
        val socket = handle.socket
        if handle.closeEmitted then released.completeUnitDiscard()
        else
            val closed: js.Function0[Unit] = () => released.completeUnitDiscard()
            discard(socket.once("close", closed))
        end if
        if !socket.destroyed.asInstanceOf[Boolean] then
            mode match
                case ReleaseMode.Abortive(_) =>
                    // `_parent` is the raw net.Socket a tls.TLSSocket wraps (null on a plain socket), the one Node can reset.
                    val raw = socket.selectDynamic("_parent")
                    discard((if js.isUndefined(raw) || raw == null then socket else raw).resetAndDestroy())
                case ReleaseMode.Graceful =>
                    def destroyNow(): Unit =
                        if !socket.destroyed.asInstanceOf[Boolean] then discard(socket.destroy())
                    val settle: js.Function0[Unit] =
                        if handle.closeFlushGrace.isFinite then
                            val timer = handle.clock.unsafe.sleep(handle.closeFlushGrace)
                            timer.onComplete(_ => destroyNow())
                            () =>
                                destroyNow()
                                // Interrupting completes the timer and runs the callback above, which finds the socket destroyed and does nothing.
                                timer.interruptDiscard(Result.Panic(Interrupted(frame, "socket settled before the close grace")))
                        else () => destroyNow()
                    // `close` covers a socket that finished before this ran or that Node destroys on an error after the end.
                    discard(socket.once("finish", settle))
                    discard(socket.once("close", settle))
                    discard(socket.end())
                    if socket.writableFinished.asInstanceOf[Boolean] then settle()
            end match
        end if
    end releaseClaimed

    def close()(using AllowUnsafe, Frame): Unit =
        if closedFlag.compareAndSet(false, true) then
            val reg = diagRegistration
            if reg ne null then reg.close()
            shutdownPromise.completeDiscard(Result.unit)
        end if
    end close

    /** The handles whose armed read is still outstanding, dropping the ones whose read has since completed or been cancelled. */
    private def pendingReadHandles()(using AllowUnsafe): List[JsHandle] =
        readArmed.filterInPlace(_.life.read.current.exists(!_.done()))
        readArmed.toList
    end pendingReadHandles

    private def deliverLeftover(handle: JsHandle, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe): Unit =
        // Deliver exactly ONE queued chunk (the oldest), the FIFO head; a later awaitRead delivers the next.
        handle.dequeueLeftover() match
            case Present(JsHandle.Leftover(buf, off, len)) =>
                val arr =
                    if off == 0 && len == buf.length then
                        // Whole-array leftover: transfer ownership directly without copying.
                        buf
                    else
                        java.util.Arrays.copyOfRange(buf, off, off + len)
                promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(arr))))
            case Absent => ()
        end match
    end deliverLeftover

    private def toNodeBuffer(span: Span[Byte], offset: Int): js.Dynamic =
        val arr = span.toArrayUnsafe
        val i8  = js.typedarray.byteArray2Int8Array(arr)
        // byteArray2Int8Array allocates a fresh ArrayBuffer (i8.byteOffset is always 0); adjust the view start by offset.
        val u8 = new js.typedarray.Uint8Array(i8.buffer, offset, i8.length - offset)
        js.Dynamic.global.Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength)
    end toNodeBuffer

end JsIoDriver

/** Factory for `JsIoDriver`. Each instance gets its own shutdown `IOPromise`. */
private[kyo] object JsIoDriver:

    /** Cap on staged probe bytes per handle; past it the probe stops resuming (see NioIoDriver.GraceProbeStagingCap for the trade). 1 MiB. */
    private[net] val PeerProbeBufferCap: Int = 1 << 20

    def init()(using AllowUnsafe): JsIoDriver =
        new JsIoDriver(new IOPromise[Any, Unit], AtomicBoolean.Unsafe.init(false))
end JsIoDriver
