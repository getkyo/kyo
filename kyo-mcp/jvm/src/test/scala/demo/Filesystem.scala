package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Path as KPath

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
        // One-time bridge: turn the CLI argument string into a kyo `Path`. `Path.of` normalises the wrapped nio path.
        // All subsequent filesystem work goes through kyo's `Path` API and typed `FileException`s.
        val root = KPath.of(java.nio.file.Paths.get(rootArg).toAbsolutePath.normalize())

        // Resolve a user-supplied relative path against the root. Rejects absolute paths and any `..` / `.`
        // segment syntactically, then delegates to `Path.confinedTo` to verify that the symlink-resolved
        // real path is still under root. For paths that don't exist yet (e.g. a write target) the
        // combined path is returned unresolved ; downstream IO surfaces the FileNotFoundException as usual.
        def resolve(input: String): KPath < (Abort[FsError] & Sync) =
            val candidate = KPath(input)
            val parts     = candidate.parts
            if candidate.isAbsolute || parts.exists(p => p == ".." || p == ".") then
                Abort.fail(FsError(reason = "path resolves outside root", path = input))
            else
                val combined = root / candidate
                combined.confinedTo(root).handle(Abort.recover[FileException] {
                    case _: FileNotFoundException     => combined
                    case _: FileAccessDeniedException => Abort.fail(FsError(reason = "path resolves outside root", path = input))
                    case ex                           => Abort.fail(FsError(reason = reasonFor(ex), path = input))
                })
            end if
        end resolve

        // Map kyo's typed `FileException` hierarchy to a short human-readable reason for the wire. Avoids the JDK's
        // `getMessage` quirk where `NoSuchFileException` and `NotDirectoryException` put the offending path in the
        // message string itself.
        def reasonFor(ex: FileException): String = ex match
            case _: FileNotFoundException          => "no such file or directory"
            case _: FileNotADirectoryException     => "not a directory"
            case _: FileIsADirectoryException      => "is a directory"
            case _: FileAccessDeniedException      => "permission denied"
            case _: FileAlreadyExistsException     => "already exists"
            case _: FileDirectoryNotEmptyException => "directory not empty"
            case _: FileIOException                => "i/o error"

        // -- read_file
        val readFileTool =
            McpHandler
                .tool[ReadFile](
                    name = "read_file",
                    description = "Read a UTF-8 text file under the configured root"
                ) { req =>
                    for
                        p <- resolve(req.path)
                        content <- p.read.handle(Abort.recover[FileReadException] { ex =>
                            Abort.fail(FsError(reason = reasonFor(ex), path = req.path))
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
                        entries <- p.list.handle(Abort.recover[FileFsException] { ex =>
                            Abort.fail(FsError(reason = reasonFor(ex), path = req.path))
                        })
                    yield McpContent.text(entries.flatMap(_.name.toChunk).mkString("\n"))
                }
                .error[FsError](code = -32001, message = "filesystem-error")

        // -- search_files (fixed-string contains across all files under root). Walks the tree streaming, reads each
        // regular file, drops unreadable entries, and returns the names that contain `pattern`.
        val searchTool =
            McpHandler
                .tool[SearchFiles](
                    name = "search_files",
                    description = "Find files containing a fixed substring"
                ) { req =>
                    val rootParts = root.parts
                    root.walk
                        .filter(_.isRegularFile)
                        .collect { file =>
                            // Read each file but swallow per-file read errors so unreadable entries don't fail the
                            // whole search. `collect` keeps only files whose contents contain the pattern.
                            file.read
                                .handle(Abort.recover[FileReadException](_ => ""))
                                .map { contents =>
                                    if contents.contains(req.pattern) then
                                        // Relative-to-root: drop the leading root parts and rejoin.
                                        Present(file.parts.drop(rootParts.size).mkString("/"))
                                    else Absent
                                }
                        }
                        .run
                        .map(hits => McpContent.text(hits.mkString("\n")))
                        .handle(Scope.run)
                }

        // -- file:///{path} resource template
        val fileTemplateUri = McpResourceUri.Template("file:///{path}")
        val fileTemplate =
            McpHandler.resourceTemplate(
                uriTemplate = fileTemplateUri,
                name = "file"
            ) { uri =>
                fileTemplateUri.extract(uri) match
                    case Absent =>
                        Chunk.empty[McpHandler.ResourceContents]
                    case Present(bindings) =>
                        val raw = bindings.getOrElse("path", "")
                        Abort.run[FsError] {
                            resolve(raw).map { p =>
                                p.read.handle(Abort.recover[FileReadException](_ => "")).map { text =>
                                    if text.isEmpty then Chunk.empty[McpHandler.ResourceContents]
                                    else
                                        Chunk(McpHandler.ResourceContents.text(
                                            uri = uri,
                                            text = text,
                                            mimeType = Present(McpMimeType("text/plain"))
                                        ))
                                }
                            }
                        }.map {
                            case Result.Success(v) => v
                            case _                 => Chunk.empty[McpHandler.ResourceContents]
                        }
                end match
            }

        // -- start the server
        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, readFileTool, listDirTool, searchTool, fileTemplate) { _ =>
                Async.never
            }
        }
    }
end Filesystem
