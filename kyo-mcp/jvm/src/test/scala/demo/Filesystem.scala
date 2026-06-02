package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server exposing a configured filesystem root.
  *
  * Three tools (`read_file`, `list_directory`, `search_files`) plus a
  * resource template (`file:///{absolutePath}`) over stdio. Each filesystem
  * failure mode maps to a distinct typed error code so callers can dispatch:
  *
  *   - `-32001 fs-not-found`       — path does not exist
  *   - `-32002 fs-access-denied`   — permission denied
  *   - `-32003 fs-not-a-directory` — listing a regular file
  *   - `-32004 fs-is-a-directory`  — reading a directory
  *   - `-32005 fs-outside-root`    — path escapes the configured root (incl. symlink escape)
  *   - `-32006 fs-already-exists`  — destination exists when it shouldn't
  *   - `-32007 fs-io-error`        — generic IO failure
  *   - `-32008 fs-bad-input`       — caller-supplied input violates contract (empty pattern, etc.)
  *
  * Pass the absolute path to the root directory as the first command-line
  * argument; defaults to the current working directory.
  */
object Filesystem extends KyoApp:

    // MARK: -- tool argument types

    case class ReadFile(path: String) derives Schema, CanEqual
    case class ListDir(path: String) derives Schema, CanEqual
    case class SearchFiles(pattern: String) derives Schema, CanEqual

    // MARK: -- typed application errors

    case class FsNotFound(path: String) derives Schema, CanEqual
    case class FsAccessDenied(path: String) derives Schema, CanEqual
    case class FsNotADirectory(path: String) derives Schema, CanEqual
    case class FsIsADirectory(path: String) derives Schema, CanEqual
    case class FsOutsideRoot(path: String) derives Schema, CanEqual
    case class FsAlreadyExists(path: String) derives Schema, CanEqual
    case class FsIoError(reason: String, path: String) derives Schema, CanEqual
    case class FsBadInput(reason: String) derives Schema, CanEqual

    type FsError =
        FsNotFound | FsAccessDenied | FsNotADirectory | FsIsADirectory | FsOutsideRoot |
            FsAlreadyExists | FsIoError | FsBadInput

    // MARK: -- main

    run {
        val rootArg = args.headOption.getOrElse(".")
        val root    = Path.of(java.nio.file.Paths.get(rootArg).toAbsolutePath.normalize())

        // Map a kyo `FileException` to the most specific typed FsError variant. Both `resolve` (path
        // pre-flight) and per-tool handlers run their FileExceptions through this so the wire error
        // shape is uniform across entry points.
        def liftFileException(ex: FileException, path: String): FsError = ex match
            case _: FileNotFoundException          => FsNotFound(path)
            case _: FileNotADirectoryException     => FsNotADirectory(path)
            case _: FileIsADirectoryException      => FsIsADirectory(path)
            case _: FileAccessDeniedException      => FsAccessDenied(path)
            case _: FileAlreadyExistsException     => FsAlreadyExists(path)
            case _: FileDirectoryNotEmptyException => FsIoError("directory not empty", path)
            case _: FileIOException                => FsIoError("i/o error", path)

        def resolve(input: String): Path < (Abort[FsError] & Sync) =
            val candidate = Path(input)
            val parts     = candidate.parts
            if candidate.isAbsolute || parts.exists(p => p == ".." || p == ".") then
                Abort.fail(FsOutsideRoot(input))
            else
                val combined = root / candidate
                combined.confinedTo(root).handle(Abort.recover[FileException] {
                    case _: FileNotFoundException     => combined
                    case _: FileAccessDeniedException => Abort.fail(FsOutsideRoot(input))
                    case ex                           => Abort.fail(liftFileException(ex, input))
                })
            end if
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
                        content <- p.read.handle(Abort.recover[FileReadException] { ex =>
                            Abort.fail(liftFileException(ex, req.path))
                        })
                    yield McpContent.text(content)
                }
                .error[FsNotFound](code = -32001, message = "fs-not-found")
                .error[FsAccessDenied](code = -32002, message = "fs-access-denied")
                .error[FsIsADirectory](code = -32004, message = "fs-is-a-directory")
                .error[FsOutsideRoot](code = -32005, message = "fs-outside-root")
                .error[FsIoError](code = -32007, message = "fs-io-error")

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
                            Abort.fail(liftFileException(ex, req.path))
                        })
                    yield McpContent.text(entries.flatMap(_.name.toChunk).mkString("\n"))
                }
                .error[FsNotFound](code = -32001, message = "fs-not-found")
                .error[FsAccessDenied](code = -32002, message = "fs-access-denied")
                .error[FsNotADirectory](code = -32003, message = "fs-not-a-directory")
                .error[FsOutsideRoot](code = -32005, message = "fs-outside-root")
                .error[FsIoError](code = -32007, message = "fs-io-error")

        // -- search_files: walks root, content-reads each regular file, returns the relative
        // paths whose content contains `pattern`. Defends against symlink-escape by funneling
        // every walked path through `confinedTo(root)`; entries that resolve outside the root
        // are silently skipped (the search is "best-effort over the safe subset of the tree").
        val searchTool =
            McpHandler
                .tool[SearchFiles](
                    name = "search_files",
                    description = "Find files containing a fixed substring (caller-supplied pattern must be non-empty)"
                ) { req =>
                    if req.pattern.isEmpty then
                        Abort.fail(FsBadInput("pattern must be non-empty"))
                    else
                        val rootParts = root.parts
                        root.walk
                            .filter(_.isRegularFile)
                            .collect { file =>
                                // confinedTo(root) follows symlinks via realPath and rejects targets outside
                                // the configured root. Filters out symlink-escapes that would otherwise leak
                                // content from system files.
                                file.confinedTo(root)
                                    .map(Present(_))
                                    .handle(Abort.recover[FileException](_ => Absent: Maybe[Path]))
                                    .map {
                                        case Absent => Absent
                                        case Present(confined) =>
                                            confined.read
                                                .handle(Abort.recover[FileReadException](_ => ""))
                                                .map { contents =>
                                                    if contents.contains(req.pattern) then
                                                        Present(file.parts.drop(rootParts.size).mkString("/"))
                                                    else Absent
                                                }
                                    }
                            }
                            .run
                            .map(hits => McpContent.text(hits.mkString("\n")))
                            .handle(Scope.run)
                    end if
                }
                .error[FsBadInput](code = -32008, message = "fs-bad-input")

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
