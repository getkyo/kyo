package kyo.internal

import java.util.Base64
import kyo.*

/** Session-scoped lazy file and directory read service for dropped items.
  *
  * One service lives in a Local per server session. Reads correlate through request ids: the server
  * sends HtmlOp.ReadDropFile or ReadDropDirectory, the browser answers with ClientMessage.FileChunk,
  * FileReadComplete, FileEntries, or FileFailure, and the service routes each response to its pending
  * request. A consumer that stops early cancels its request through Scope.ensure, and peer close fails
  * every pending read with Disconnected and clears the map. All byte quantities are ByteSize; raw
  * numbers appear only at the channel capacity computation.
  */
private[kyo] object DragFiles:

    /** Server-side transfer ceilings applied to every read request. */
    final private[kyo] case class TransferLimits(
        maxChunkSize: ByteSize = 1.mib,
        maxBufferedSize: ByteSize = 8.mib
    ) derives CanEqual

    private[kyo] type FileSignal      = Result[Drag.FileError, Maybe[Chunk[Byte]]]
    private[kyo] type DirectorySignal = Result[Drag.FileError, (Chunk[DragProtocol.EntryData], Maybe[String])]

    sealed private[kyo] trait Pending derives CanEqual
    final private[kyo] case class PendingFile(channel: Channel[FileSignal])                extends Pending
    final private[kyo] case class PendingDirectory(promise: Promise[DirectorySignal, Any]) extends Pending

    private[kyo] val local: Local[Maybe[Service]] = Local.init(Absent)

    private[kyo] def use[A, S](f: Service => A < (S & Async & Abort[Drag.FileError]))(using
        Frame
    ): A < (S & Async & Abort[Drag.FileError]) =
        local.use {
            case Present(service) => f(service)
            case Absent           => Abort.fail(Drag.FileError.Disconnected)
        }

    final private[kyo] class Service(
        send: HtmlOp => Unit < Async,
        limits: TransferLimits,
        pending: AtomicRef[Map[String, Pending]],
        sequence: AtomicLong
    ):

        private[kyo] def pendingCount(using Frame): Int < Sync = pending.get.map(_.size)

        /** Streams a dropped file's bytes in `chunkSize` requests, one request in flight at a time. */
        def readFile(meta: Drag.FileMeta, chunkSize: ByteSize)(using
            Frame
        ): Stream[Byte, Async & Scope & Abort[Drag.FileError]] =
            Stream.unwrap {
                for
                    _         <- validateChunkSize(chunkSize)
                    capacity  <- channelCapacity(chunkSize)
                    channel   <- Channel.initUnscoped[FileSignal](capacity)
                    requestId <- newRequestId("file")
                    _         <- register(requestId, PendingFile(channel))
                    _         <- Scope.ensure(cancelRequest(requestId))
                    offset    <- AtomicRef.init(ByteSize.Zero)
                yield
                    // Sequential protocol: one request per iteration at the current offset. The reply past the
                    // end is FileReadComplete, which ends the stream through the Absent signal.
                    def consume(result: Maybe[Chunk[Byte]]): Maybe[Seq[Byte]] < (Async & Abort[Drag.FileError]) =
                        result match
                            case Present(bytes) =>
                                offset.updateAndGet(_ + ByteSize.fromBytes(bytes.size.toLong))
                                    .andThen(Present(bytes.toSeq))
                            case Absent => Absent
                    Stream.repeatPresent[Byte, Async & Abort[Drag.FileError]] {
                        for
                            read   <- offset.get
                            _      <- send(HtmlOp.ReadDropFile(requestId, meta.token, read, chunkSize))
                            signal <- Abort.recover[Closed](_ => Result.fail(Drag.FileError.Disconnected))(channel.take)
                            result <- Abort.get(signal)
                            next   <- consume(result)
                        yield next
                    }
            }
        end readFile

        /** Streams directory entries page by page until the browser reports an absent cursor. */
        def readDirectory(token: String, directoryLimits: Drag.DirectoryLimits)(using
            Frame
        ): Stream[DragProtocol.EntryData, Async & Scope & Abort[Drag.FileError]] =
            Stream.unwrap {
                for
                    _      <- validateDirectoryLimits(directoryLimits)
                    cursor <- AtomicRef.init(Present(Absent): Maybe[Maybe[String]])
                    seen   <- AtomicRef.init(0)
                yield
                    def page(state: Maybe[String]): Maybe[Seq[DragProtocol.EntryData]] < (Async & Scope & Abort[Drag.FileError]) =
                        for
                            remaining <- seen.get.map(count => directoryLimits.maxEntries - count)
                            _ <- Abort.when(remaining <= 0)(
                                Drag.FileError.LimitExceeded("Directory entry limit reached.")
                            )
                            requestId <- newRequestId("directory")
                            promise   <- Promise.init[DirectorySignal, Any]
                            _         <- register(requestId, PendingDirectory(promise))
                            _         <- Scope.ensure(cancelRequest(requestId))
                            _         <- send(HtmlOp.ReadDropDirectory(requestId, token, state, remaining))
                            signal    <- promise.get
                            pair      <- Abort.get(signal)
                            _         <- seen.updateAndGet(_ + pair._1.size)
                            _         <- cursor.set(pair._2.map(value => Present(Present(value))).getOrElse(Absent))
                            _         <- unregister(requestId)
                        yield Present(pair._1.toSeq)
                    def step(state: Maybe[Maybe[String]]): Maybe[Seq[DragProtocol.EntryData]] < (Async & Scope & Abort[Drag.FileError]) =
                        state match
                            case Absent        => Absent
                            case Present(next) => page(next)
                    Stream.repeatPresent[DragProtocol.EntryData, Async & Scope & Abort[Drag.FileError]] {
                        cursor.get.map(step)
                    }
            }
        end readDirectory

        /** Routes one browser file transfer response to its pending request. Unknown ids are dropped. */
        def deliver(message: DragProtocol.ClientMessage)(using Frame): Unit < Async =
            message match
                case DragProtocol.ClientMessage.FileChunk(requestId, bytesBase64) =>
                    withPending(requestId) {
                        case PendingFile(channel) =>
                            decodeBase64(bytesBase64) match
                                case Present(bytes) => Abort.run(channel.put(Result.succeed(Present(bytes)))).unit
                                case Absent =>
                                    Abort.run(channel.put(Result.fail(Drag.FileError.Io("Malformed base64 chunk.")))).unit
                        case _ => ()
                    }
                case DragProtocol.ClientMessage.FileReadComplete(requestId) =>
                    withPending(requestId) {
                        case PendingFile(channel) => unregister(requestId).andThen(Abort.run(channel.put(Result.succeed(Absent))).unit)
                        case _                    => ()
                    }
                case DragProtocol.ClientMessage.FileEntries(requestId, entries, nextCursor) =>
                    withPending(requestId) {
                        case PendingDirectory(promise) =>
                            promise.completeDiscard(Result.succeed(Result.succeed((entries, nextCursor)): DirectorySignal))
                        case _ => ()
                    }
                case DragProtocol.ClientMessage.FileFailure(requestId, failure) =>
                    val error = failureError(failure)
                    withPending(requestId) {
                        case PendingFile(channel) =>
                            unregister(requestId).andThen(Abort.run(channel.put(Result.fail(error))).unit)
                        case PendingDirectory(promise) =>
                            unregister(requestId).andThen(promise.completeDiscard(Result.succeed(Result.fail(error): DirectorySignal)))
                    }
                case _: DragProtocol.ClientMessage.Event => ()
        end deliver

        /** Fails every pending read with Disconnected and clears the map; safe to call more than once. */
        def close()(using Frame): Unit < Async =
            pending.getAndSet(Map.empty).map { entries =>
                Kyo.foreachDiscard(Chunk.from(entries.values)) {
                    case PendingFile(channel) =>
                        Abort.run(channel.put(Result.fail(Drag.FileError.Disconnected))).unit
                    case PendingDirectory(promise) =>
                        promise.completeDiscard(Result.succeed(Result.fail(Drag.FileError.Disconnected): DirectorySignal))
                }
            }

        private def withPending(requestId: String)(f: Pending => Unit < Async)(using Frame): Unit < Async =
            pending.get.map(_.get(requestId) match
                case Some(value) => f(value)
                case None        => ())

        private def register(requestId: String, value: Pending)(using Frame): Unit < Sync =
            pending.updateAndGet(_ + (requestId -> value)).unit

        private def unregister(requestId: String)(using Frame): Unit < Sync =
            pending.updateAndGet(_ - requestId).unit

        private def cancelRequest(requestId: String)(using Frame): Unit < Async =
            pending.get.map { entries =>
                if entries.contains(requestId) then
                    unregister(requestId).andThen(Abort.run(send(HtmlOp.CancelDropRead(requestId))).unit)
                else ()
            }

        private def newRequestId(prefix: String)(using Frame): String < Sync =
            sequence.incrementAndGet.map(value => s"$prefix-$value")

        private def validateChunkSize(chunkSize: ByteSize)(using Frame): Unit < Abort[Drag.FileError] =
            if chunkSize <= ByteSize.Zero then
                Abort.fail(Drag.FileError.LimitExceeded("Chunk size must be positive."))
            else if chunkSize > limits.maxChunkSize then
                Abort.fail(Drag.FileError.LimitExceeded(s"Chunk size exceeds ${limits.maxChunkSize.show}."))
            else if limits.maxBufferedSize < chunkSize then
                Abort.fail(Drag.FileError.LimitExceeded("Buffered ceiling is below the chunk size."))
            else ()

        private def validateDirectoryLimits(value: Drag.DirectoryLimits)(using Frame): Unit < Abort[Drag.FileError] =
            if value.maxDepth <= 0 || value.maxEntries <= 0 || value.maxSize <= ByteSize.Zero then
                Abort.fail(Drag.FileError.LimitExceeded("Directory limits must be positive."))
            else ()

        /** Bounded channel capacity as a chunk count; the quotient must fit an Int. */
        private def channelCapacity(chunkSize: ByteSize)(using Frame): Int < Abort[Drag.FileError] =
            val buffered = limits.maxBufferedSize.toBytes
            val chunk    = chunkSize.toBytes
            val quotient = buffered / chunk
            if quotient <= 0 || quotient > Int.MaxValue.toLong then
                Abort.fail(Drag.FileError.LimitExceeded("Buffered ceiling does not yield a valid chunk count."))
            else quotient.toInt
        end channelCapacity

        private def decodeBase64(value: String): Maybe[Chunk[Byte]] =
            try
                val decoded = Base64.getDecoder.decode(value)
                if decoded.isEmpty then Absent else Present(Chunk.from(decoded.toIndexedSeq))
            catch case _: IllegalArgumentException => Absent

        private def failureError(failure: DragProtocol.FileFailureData): Drag.FileError =
            failure match
                case DragProtocol.FileFailureData.InvalidToken          => Drag.FileError.InvalidToken
                case DragProtocol.FileFailureData.PermissionDenied      => Drag.FileError.PermissionDenied
                case DragProtocol.FileFailureData.NotFound              => Drag.FileError.NotFound
                case DragProtocol.FileFailureData.LimitExceeded(reason) => Drag.FileError.LimitExceeded(reason)
                case DragProtocol.FileFailureData.Io(reason)            => Drag.FileError.Io(reason)
    end Service

    private[kyo] object Service:
        def init(send: HtmlOp => Unit < Async, limits: TransferLimits = TransferLimits())(using Frame): Service < Sync =
            for
                pending  <- AtomicRef.init(Map.empty[String, Pending])
                sequence <- AtomicLong.init(0)
            yield new Service(send, limits, pending, sequence)
    end Service

end DragFiles
