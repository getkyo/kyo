package sttp.client3

import kyo.*
import kyo.Emit.Ack
import kyo.Result.Success
import kyo.capabilities.*
import kyo.capabilities.KyoStreams
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import kyo.sink
import sttp.capabilities.Streams
import sttp.client3.internal.BodyFromResponseAs
import sttp.client3.internal.SttpFile
import sttp.client3.internal.httpclient.BodyFromHttpClient
import sttp.client3.ws.GotAWebSocketException
import sttp.client3.ws.NotAWebSocketException
import sttp.model.ResponseMetadata
import sttp.monad.MonadError
import sttp.ws.WebSocket
import sttp.ws.WebSocketClosed
import sttp.ws.WebSocketFrame

final class KyoBodyFromHttpClient extends BodyFromHttpClient[KyoSttpMonad.M, KyoStreams, KyoStreams.BinaryStream]:
    override val streams: KyoStreams                 = KyoStreams
    override given monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad

    override protected def bodyFromResponseAs
        : BodyFromResponseAs[KyoSttpMonad.M, KyoStreams.BinaryStream, WebSocket[KyoSttpMonad.M], KyoStreams.BinaryStream] =
        new BodyFromResponseAs[KyoSttpMonad.M, KyoStreams.BinaryStream, WebSocket[KyoSttpMonad.M], KyoStreams.BinaryStream]:
            override protected def withReplayableBody(
                response: KyoStreams.BinaryStream,
                replayableBody: Either[Array[Byte], SttpFile]
            ): KyoSttpMonad.M[KyoStreams.BinaryStream] =
                replayableBody match
                    case Left(byteArray) => Stream.init(Chunk.from(byteArray))
                    case Right(file)     => IO(Path.fromJavaPath(file.toPath)).map(_.readBytesStream)
            override protected def regularIgnore(response: KyoStreams.BinaryStream): KyoSttpMonad.M[Unit] =
                Resource.run(response.runDiscard)

            override protected def regularAsByteArray(
                response: KyoStreams.BinaryStream
            ): KyoSttpMonad.M[Array[Byte]] =
                Resource.run(response.run.map(_.toList.toArray))

            override protected def regularAsFile(
                response: KyoStreams.BinaryStream,
                file: SttpFile
            ): KyoSttpMonad.M[SttpFile] = IO(Path.fromJavaPath(file.toPath))
                .map(path => Resource.run(response.sink(path)))
                .map(_ => file)

            override protected def regularAsStream(
                response: KyoStreams.BinaryStream
            ): KyoSttpMonad.M[(KyoStreams.BinaryStream, () => KyoSttpMonad.M[Unit])] =
                IO(response, () => Resource.run(response.runDiscard))

            override protected def handleWS[T](
                responseAs: WebSocketResponseAs[T, ?],
                meta: ResponseMetadata,
                ws: WebSocket[KyoSttpMonad.M]
            ): KyoSttpMonad.M[T] = bodyFromWs(responseAs, ws, meta)

            override protected def cleanupWhenNotAWebSocket(
                response: KyoStreams.BinaryStream,
                e: NotAWebSocketException
            ): KyoSttpMonad.M[Unit] = Resource.run(response.runDiscard)

            override protected def cleanupWhenGotWebSocket(
                response: WebSocket[KyoSttpMonad.M],
                e: GotAWebSocketException
            ): KyoSttpMonad.M[Unit] = response.close()
    end bodyFromResponseAs

    override def compileWebSocketPipe(
        ws: WebSocket[KyoSttpMonad.M],
        pipe: KyoStreams.Pipe[WebSocketFrame.Data[?], WebSocketFrame]
    ): KyoSttpMonad.M[Unit] =
        def receiveFrame: Result[WebSocketClosed, WebSocketFrame] < Async =
            Abort.run(Abort.catching[WebSocketClosed](ws.receive()))

        def emitFromWebSocket: Ack < (Emit[Chunk[WebSocketFrame.Data[?]]] & Async) =
            Loop[Unit, Ack, Emit[Chunk[WebSocketFrame.Data[?]]] & Async](()) { _ =>
                receiveFrame.map {
                    case Success(WebSocketFrame.Close(_, _)) => Loop.done[Unit, Ack](Ack.Stop)
                    case Success(WebSocketFrame.Ping(payload)) =>
                        ws.send(WebSocketFrame.Pong(payload)).andThen(Loop.continue[Ack])
                    case Success(WebSocketFrame.Pong(_)) => Loop.continue[Ack]
                    case Success(in: WebSocketFrame.Data[?]) => Emit.andMap(Chunk(in)) {
                            case Ack.Stop => Loop.done[Unit, Ack](Ack.Stop)
                            case _        => Loop.continue[Ack]
                        }
                    case _ => Loop.done[Unit, Ack](Ack.Stop)
                }
            }

        val pipeComputation: Unit < (Async & Resource) = pipe(Stream(emitFromWebSocket))
            .runForeach(dataFrame => ws.send(dataFrame))
            .andThen(Resource.ensure(ws.close()))

        Resource.run(pipeComputation)
    end compileWebSocketPipe

end KyoBodyFromHttpClient
