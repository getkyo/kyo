package kyo

/** Stream sinks that write byte and text streams to the file system.
  *
  * These live in kyo-system rather than kyo-core because they are typed in terms of [[kyo.Path]] and [[kyo.FileException]]. Keeping them in
  * kyo-core would make kyo-core depend on kyo-system, which already depends on kyo-core.
  *
  * @see
  *   [[kyo.Path]] for the path type these sinks target
  */
object StreamFileExtensions:

    /** Shared write logic: opens a write handle via Scope, runs the body, and removes the partial file on failure. */
    private def writeWith[S](path: Path)(
        body: Path.WriteHandle => Unit < (Sync & Abort[FileWriteException] & S)
    )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
        Scope
            .acquireRelease(
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
            System.lineSeparator.map { sep =>
                writeWith(path) { handle =>
                    stream.foreach { s =>
                        Sync.Unsafe.defer(Abort.get(handle.writeString(s + sep, charset)))
                    }
                }
            }
    end extension

end StreamFileExtensions

export StreamFileExtensions.*
