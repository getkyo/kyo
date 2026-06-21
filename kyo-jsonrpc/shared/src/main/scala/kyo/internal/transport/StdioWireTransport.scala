package kyo.internal.transport

import java.io.EOFException
import kyo.*

final private[kyo] class StdioWireTransport extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        val line    = new String(bytes.toArray, "UTF-8")
        val trimmed = if line.endsWith("\n") then line.dropRight(1) else line
        Console.printLine(trimmed)
    end send

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        // chunkSize = 1 so each readLine emits a chunk immediately. Without this the default
        // chunkSize batches reads, which blocks the MCP/LSP handshake against any
        // spec-compliant host that holds stdin open across the session (Claude Code,
        // VS Code, Cursor, etc.); the first response only flushes once enough requests
        // accumulate to fill the default chunk, or stdin closes.
        Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]((), chunkSize = 1) { _ =>
            // EOFException from Console.readLine signals stream end; absorbed into Absent to close the stream
            Abort.run[java.io.IOException](Console.readLine).map {
                case Result.Failure(_) => Maybe.Absent
                case Result.Panic(_)   => Maybe.Absent
                case Result.Success(line) =>
                    val bytes = Chunk.from((line + "\n").getBytes("UTF-8"))
                    Maybe.Present((bytes, ()))
            }
        }

    def close(using Frame): Unit < Async = Kyo.unit
end StdioWireTransport
