package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server demonstrating the resources/subscribe protocol.
  *
  * One resource (`logtail://current`) marked `subscribe = true` that returns the current
  * contents of a configured file. A background fiber polls the file's last-modified time
  * and size; on change it calls [[McpServer.notifyResourceUpdated]], which the engine
  * delivers to every peer that previously subscribed via `resources/subscribe`.
  *
  * Pass the absolute path of the file to tail as the first command-line argument. The
  * file does not need to exist at startup; reads return an empty string until the file
  * appears, and the watcher picks up the first write as an update.
  */
object LogTail extends KyoApp:

    private val ResourceUri = McpResourceUri("logtail://current")

    run {
        val tailArg  = args.headOption.getOrElse("/tmp/mcp-validation/logtail.txt")
        val tailPath = Path.of(java.nio.file.Paths.get(tailArg).toAbsolutePath.normalize())

        val readCurrent: String < Sync =
            tailPath.read.handle(Abort.recover[FileReadException](_ => ""))

        val tailResource =
            McpHandler.resource(
                ResourceUri,
                "logtail-current",
                "Current contents of the configured log file. Subscribe for updates.",
                Present(McpMimeType("text/plain")),
                McpHandler.ResourceAnnotations.noop,
                true
            ) {
                readCurrent.map { text =>
                    Chunk(McpHandler.ResourceContents.text(uri = ResourceUri, text = text, mimeType = Present(McpMimeType("text/plain"))))
                }
            }

        JsonRpcTransport.stdio().map { t =>
            McpServer.initUnscopedWith(t, tailResource) { server =>
                // Spawn a watcher fiber that polls the file's content every 500 ms and emits
                // `notifications/resources/updated` whenever the content changes from the
                // previous poll. Content-equality is the change signal because kyo.Path does
                // not yet expose mtime; the small file size in demos makes the cost acceptable.
                val watcher: Unit < (Async & Abort[Closed]) =
                    Loop(Maybe.empty[String]) { prev =>
                        for
                            _   <- Async.sleep(500.millis)
                            cur <- readCurrent
                            _ <-
                                if prev.exists(_ != cur) then server.notifyResourceUpdated(ResourceUri)
                                else ((): Unit): Unit < Sync
                        yield Loop.continue(Present(cur))
                    }
                Fiber.initUnscoped(watcher).map(_ => Async.never)
            }
        }
    }
end LogTail
