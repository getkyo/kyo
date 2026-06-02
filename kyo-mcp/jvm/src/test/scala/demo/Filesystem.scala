package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server exposing a configured filesystem root.
  *
  * Three tools (`read_file`, `list_directory`, `search_files`) plus a
  * resource template (`file:///{absolutePath}`) over stdio. Path-traversal
  * escapes return a typed `FsError` via `McpHandler.error[E2]`.
  *
  * Pass the absolute path to the root directory as the first command-line
  * argument; defaults to the current working directory.
  *
  * To drive from Claude Desktop, register in `claude_desktop_config.json`
  * under stdio transport.
  */
object Filesystem extends KyoApp:

    // MARK: -- tool argument types

    case class ReadFile(path: String) derives Schema, CanEqual
    case class ListDir(path: String) derives Schema, CanEqual
    case class SearchFiles(pattern: String) derives Schema, CanEqual

    // MARK: -- typed application error

    case class FsError(reason: String, path: String) derives Schema, CanEqual

    // MARK: -- main

    run {
        val rootArg = args.headOption.getOrElse(".")
        val root    = java.nio.file.Paths.get(rootArg).toAbsolutePath.normalize()

        // Resolve a user-supplied path against the root, rejecting escapes.
        def resolve(input: String): java.nio.file.Path < Abort[FsError] =
            val candidate = root.resolve(input).normalize()
            if candidate.startsWith(root) then candidate
            else Abort.fail(FsError(reason = "path resolves outside root", path = input))
        end resolve

        // -- read_file
        val readFileTool =
            McpHandler
                .tool[ReadFile](
                    name = "read_file",
                    description = "Read a UTF-8 text file under the configured root"
                ) { req =>
                    for
                        p <- resolve(req.path)
                        content <- Abort.catching[java.io.IOException](
                            Sync.defer(java.nio.file.Files.readString(p))
                        ).handle(Abort.recover[java.io.IOException] { ex =>
                            Abort.fail(FsError(reason = ex.getMessage, path = req.path))
                        })
                    yield McpContent.text(content)
                }
                .error[FsError](code = -32001, message = "filesystem-error")

        // -- list_directory
        val listDirTool =
            McpHandler
                .tool[ListDir](
                    name = "list_directory",
                    description = "List entries directly under a directory path"
                ) { req =>
                    for
                        p <- resolve(req.path)
                        entries <- Abort.catching[java.io.IOException](
                            Sync.defer {
                                import scala.jdk.CollectionConverters.*
                                val stream = java.nio.file.Files.list(p)
                                try Chunk.from(stream.iterator.asScala.toList.map(_.getFileName.toString))
                                finally stream.close()
                            }
                        ).handle(Abort.recover[java.io.IOException] { ex =>
                            Abort.fail(FsError(reason = ex.getMessage, path = req.path))
                        })
                    yield McpContent.text(entries.mkString("\n"))
                }
                .error[FsError](code = -32001, message = "filesystem-error")

        // -- search_files (fixed-string contains across all files under root)
        val searchTool =
            McpHandler
                .tool[SearchFiles](
                    name = "search_files",
                    description = "Find files containing a fixed substring"
                ) { req =>
                    Sync.defer {
                        import scala.jdk.CollectionConverters.*
                        val stream = java.nio.file.Files.walk(root)
                        try
                            val hits = stream
                                .iterator
                                .asScala
                                .filter(java.nio.file.Files.isRegularFile(_))
                                .filter(p =>
                                    try java.nio.file.Files.readString(p).contains(req.pattern)
                                    catch case _: Throwable => false
                                )
                                .map(root.relativize(_).toString)
                                .toList
                            McpContent.text(hits.mkString("\n"))
                        finally stream.close()
                        end try
                    }
                }

        // -- file:///{absolutePath} resource template
        val fileTemplate =
            McpHandler.resourceTemplate(
                uriTemplate = McpResourceUri.Template("file:///{path}"),
                name = "file"
            ) { uri =>
                // The matched URI string is what the client requested.
                // Extract the {path} segment, resolve it, read the file.
                val raw = uri.asString.stripPrefix("file:///")
                Sync.defer {
                    val p = root.resolve(raw).normalize()
                    if !p.startsWith(root) then
                        Chunk.empty[McpHandler.ResourceContents]
                    else
                        Chunk(McpHandler.ResourceContents.text(
                            uri = uri,
                            text = java.nio.file.Files.readString(p),
                            mimeType = Present(McpMimeType("text/plain"))
                        ))
                    end if
                }
            }

        // -- start the server
        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, readFileTool, listDirTool, searchTool, fileTemplate) { _ =>
                Async.never
            }
        }
    }
end Filesystem
