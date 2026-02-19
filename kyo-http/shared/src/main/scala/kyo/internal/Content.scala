package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

sealed private[kyo] trait Content derives CanEqual:
    def isStreaming: Boolean = false
end Content

private[kyo] object Content:
    sealed trait BytesInput extends Content:
        /** Server-side: extract body value from request. */
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Bytes])(using Frame): Result[HttpError, Any]

        /** Client-side: encode value to (bytes, contentType). Returns Maybe.Absent for streaming types. */
        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)]
    end BytesInput

    sealed trait StreamInput extends Content:
        override def isStreaming: Boolean = true

        /** Server-side: extract body value from streaming request. */
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Streamed])(using Frame): Any < (Sync & Abort[HttpError.ParseError])

        /** Client-side: encode value to (bytes, contentType). Always Absent for streaming types. */
        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)] = Absent

        /** Client-side: encode value to a byte stream for streaming request body. */
        private[kyo] def encodeStreamTo(value: Any)(using Frame): Stream[Span[Byte], Async]
    end StreamInput

    sealed trait BytesOutput extends Content:
        /** Server-side: encode value to a response. */
        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?]

        /** Client-side: decode buffered response body to value. */
        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any]
    end BytesOutput

    sealed trait StreamOutput extends BytesOutput:
        override def isStreaming: Boolean = true

        /** Client-side: decode streaming response body to stream value. */
        private[kyo] def decodeStreamFrom(response: HttpResponse[HttpBody.Streamed])(using
            Frame
        ): Any < (Sync & Abort[HttpError.ParseError])
    end StreamOutput

    case class Json(schema: Schema[Any]) extends BytesInput with BytesOutput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            schema.decode(request.bodyText).mapFailure(HttpError.ParseError(_))

        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)] =
            Present((schema.encode(value).getBytes("UTF-8"), "application/json"))

        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            HttpResponse.json(status, schema.encode(value))

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            schema.decode(response.bodyText).mapFailure(HttpError.ParseError(_))
    end Json

    case object Text extends BytesInput with BytesOutput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(request.bodyText)

        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)] =
            Present((value.toString.getBytes("UTF-8"), "text/plain"))

        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            HttpResponse(status, value.toString)

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(response.bodyText)
    end Text

    case object Binary extends BytesInput with BytesOutput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(request.bodyBytes)

        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)] =
            Present((value.asInstanceOf[Span[Byte]].toArray, "application/octet-stream"))

        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            HttpResponse(status, value.asInstanceOf[Span[Byte]])

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(response.bodyBytes)
    end Binary

    case object ByteStream extends StreamInput with StreamOutput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Streamed])(using Frame): Any < (Sync & Abort[HttpError.ParseError]) =
            request.bodyStream

        private[kyo] def encodeStreamTo(value: Any)(using Frame): Stream[Span[Byte], Async] =
            value.asInstanceOf[Stream[Span[Byte], Async]]

        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            HttpResponse.stream(value.asInstanceOf[Stream[Span[Byte], Async]], status)

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(response.bodyBytes)

        private[kyo] def decodeStreamFrom(response: HttpResponse[HttpBody.Streamed])(using
            Frame
        ): Any < (Sync & Abort[HttpError.ParseError]) =
            response.bodyStream
    end ByteStream

    case class Ndjson(schema: Schema[Any], emitTag: Tag[Emit[Chunk[Any]]]) extends StreamInput with StreamOutput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Streamed])(using Frame): Any < (Sync & Abort[HttpError.ParseError]) =
            Sync.Unsafe.defer {
                val decoder = NdjsonDecoder.init(schema)
                request.bodyStream.mapChunk[Span[Byte], Any, Sync & Abort[HttpError.ParseError]] { chunk =>
                    Sync.Unsafe.defer {
                        decodeChunk(decoder, chunk, 0, Seq.newBuilder[Any])
                    }
                }
            }
        end decodeFrom

        private def decodeChunk(
            decoder: NdjsonDecoder[Any],
            chunk: Chunk[Span[Byte]],
            i: Int,
            result: scala.collection.mutable.Builder[Any, Seq[Any]]
        )(using Frame, AllowUnsafe): Seq[Any] < Abort[HttpError.ParseError] =
            if i >= chunk.size then result.result()
            else
                decoder.decode(chunk(i)) match
                    case Result.Success(values) =>
                        discard(result ++= values)
                        decodeChunk(decoder, chunk, i + 1, result)
                    case fail => Abort.get(fail)

        private[kyo] def encodeStreamTo(value: Any)(using Frame): Stream[Span[Byte], Async] =
            value.asInstanceOf[Stream[Any, Async]].map { v =>
                Span.fromUnsafe((schema.encode(v) + "\n").getBytes(StandardCharsets.UTF_8))
            }

        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            val byteStream = value.asInstanceOf[Stream[Any, Async]].map { v =>
                Span.fromUnsafe((schema.encode(v) + "\n").getBytes(StandardCharsets.UTF_8))
            }
            HttpResponse.stream(byteStream, status)
                .setHeader("Content-Type", "application/x-ndjson")
        end encodeToResponse

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(()) // buffered decode not meaningful for streaming

        private[kyo] def decodeStreamFrom(response: HttpResponse[HttpBody.Streamed])(using
            Frame
        ): Any < (Sync & Abort[HttpError.ParseError]) =
            Sync.Unsafe.defer {
                val decoder                 = NdjsonDecoder.init(schema)
                given Tag[Emit[Chunk[Any]]] = emitTag
                response.bodyStream.mapChunk[Span[Byte], Any, Sync & Abort[HttpError.ParseError]] { chunk =>
                    Sync.Unsafe.defer {
                        decodeChunk(decoder, chunk, 0, Seq.newBuilder[Any])
                    }
                }
            }
    end Ndjson

    case object Multipart extends BytesInput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(request.parts.toArrayUnsafe.toSeq)

        private[kyo] def encodeTo(value: Any): Maybe[(Array[Byte], String)] =
            Absent // Multipart encoding handled specially by HttpRequest.multipart
    end Multipart

    case object MultipartStream extends StreamInput:
        private[kyo] def decodeFrom(request: HttpRequest[HttpBody.Streamed])(using Frame): Any < Sync =
            val boundary = request.contentType match
                case Present(ct) => MultipartUtil.extractBoundary(ct)
                case Absent      => Absent
            boundary match
                case Present(b) =>
                    Sync.Unsafe.defer {
                        val decoder = MultipartStreamDecoder.init(b)
                        request.bodyStream.mapChunk[Span[Byte], HttpRequest.Part, Sync] { chunk =>
                            Sync.Unsafe.defer {
                                val result = Seq.newBuilder[HttpRequest.Part]
                                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                                result.result()
                            }
                        }
                    }
                case Absent =>
                    throw new IllegalArgumentException("Missing multipart boundary")
            end match
        end decodeFrom

        private[kyo] def encodeStreamTo(value: Any)(using Frame): Stream[Span[Byte], Async] =
            throw new UnsupportedOperationException(
                "Streaming multipart client calls are not yet supported. Use bodyMultipart for buffered multipart."
            )
    end MultipartStream

    case class Sse(schema: Schema[Any], emitTag: Tag[Emit[Chunk[HttpEvent[Any]]]]) extends StreamOutput:
        private[kyo] def encodeToResponse(value: Any, status: HttpStatus)(using Frame): HttpResponse[?] =
            val stream = value.asInstanceOf[Stream[HttpEvent[Any], Async]]
            val byteStream = stream.map { event =>
                val sb = new StringBuilder
                event.event.foreach(e => discard(sb.append("event: ").append(e).append('\n')))
                event.id.foreach(id => discard(sb.append("id: ").append(id).append('\n')))
                event.retry.foreach(d => discard(sb.append("retry: ").append(d.toMillis).append('\n')))
                discard(sb.append("data: ").append(schema.encode(event.data)).append('\n'))
                discard(sb.append('\n'))
                Span.fromUnsafe(sb.toString.getBytes(StandardCharsets.UTF_8))
            }
            HttpResponse.stream(byteStream, status)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Cache-Control", "no-cache")
                .setHeader("Connection", "keep-alive")
        end encodeToResponse

        private[kyo] def decodeFrom(response: HttpResponse[HttpBody.Bytes])(using Frame): Result[HttpError, Any] =
            Result.succeed(()) // buffered decode not meaningful for streaming

        private[kyo] def decodeStreamFrom(response: HttpResponse[HttpBody.Streamed])(using
            Frame
        ): Any < (Sync & Abort[HttpError.ParseError]) =
            Sync.Unsafe.defer {
                val decoder                            = SseDecoder.init(schema)
                given Tag[Emit[Chunk[HttpEvent[Any]]]] = emitTag
                response.bodyStream.mapChunk[Span[Byte], HttpEvent[Any], Sync & Abort[HttpError.ParseError]] { chunk =>
                    Sync.Unsafe.defer {
                        decodeSseChunk(decoder, chunk, 0, Seq.newBuilder[HttpEvent[Any]])
                    }
                }
            }

        private def decodeSseChunk(
            decoder: SseDecoder[Any],
            chunk: Chunk[Span[Byte]],
            i: Int,
            result: scala.collection.mutable.Builder[HttpEvent[Any], Seq[HttpEvent[Any]]]
        )(using Frame, AllowUnsafe): Seq[HttpEvent[Any]] < Abort[HttpError.ParseError] =
            if i >= chunk.size then result.result()
            else
                decoder.decode(chunk(i)) match
                    case Result.Success(events) =>
                        discard(result ++= events)
                        decodeSseChunk(decoder, chunk, i + 1, result)
                    case fail => Abort.get(fail)
    end Sse
end Content
