package kyo

/** Stream extensions that write byte and text streams to the file system.
  *
  * These sinks acquire a write handle in a `Scope`, stream the content, and remove the partially-written file on failure. They live in
  * kyo-system alongside [[kyo.Path]] because they couple to the file capability; kyo-core keeps the platform-neutral stream combinators.
  *
  * @see
  *   [[kyo.Path]] for the file-path capability the sinks target
  */
object StreamFileExtensions:

    /** Shared write logic: opens a write handle via Scope, runs the body, and removes the partial file on failure. */
    private def writeWith[S](path: Path)(
        body: Path.WriteHandle => Unit < (Sync & Abort[FileWriteException] & S)
    )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
        Scope
            .acquireRelease(
                // Unsafe: bridges Path.Unsafe.openWrite into the safe tier; the handle is released by the enclosing Scope.
                Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append = false, createFolders = true)))
            )(handle => Sync.Unsafe.defer(handle.close()))
            .map { handle =>
                Abort.run[FileWriteException](body(handle)).map {
                    case Result.Failure(e) =>
                        path.remove.andThen(Abort.fail(e))
                    case ok => Abort.get(ok)
                }
            }

    extension [S](stream: Stream[Byte, S])
        /** Writes each byte of the stream to `path`, creating parent directories as needed.
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error.
          */
        def writeTo(path: Path)(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeWith(path) { handle =>
                stream.foreachChunk { chunk =>
                    // Unsafe: bridges Path.WriteHandle.writeBytes into the safe tier under the acquired Scope handle.
                    Sync.Unsafe.defer(Abort.get(handle.writeBytes(chunk)))
                }
            }
    end extension

    extension [S](stream: Stream[String, S])
        /** Writes each string chunk of the stream to `path` using the given charset (default UTF-8).
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails.
          */
        def writeTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8
        )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeWith(path) { handle =>
                stream.foreach { s =>
                    // Unsafe: bridges Path.WriteHandle.writeString into the safe tier under the acquired Scope handle.
                    Sync.Unsafe.defer(Abort.get(handle.writeString(s, charset)))
                }
            }

        /** Writes each string element as a separate line to `path` using the given charset.
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error.
          */
        def writeLinesTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8
        )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            Sync.defer(java.lang.System.lineSeparator()).map { sep =>
                writeWith(path) { handle =>
                    stream.foreach { s =>
                        // Unsafe: bridges Path.WriteHandle.writeString into the safe tier under the acquired Scope handle.
                        Sync.Unsafe.defer(Abort.get(handle.writeString(s + sep, charset)))
                    }
                }
            }
    end extension
end StreamFileExtensions

export StreamFileExtensions.*
