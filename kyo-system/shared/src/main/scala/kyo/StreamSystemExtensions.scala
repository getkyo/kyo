package kyo

/** Stream sinks that write byte and text streams to the file system.
  *
  * These live in kyo-system rather than kyo-core because they are typed in terms of [[kyo.Path]] and [[kyo.FileException]]. Keeping them in
  * kyo-core would make kyo-core depend on kyo-system, which already depends on kyo-core.
  *
  * @see
  *   [[kyo.Path]] for the path type these sinks target
  */
object StreamSystemExtensions:

    /** Shared write logic: opens a write handle via Scope, runs the body, and removes the partial file on failure.
      *
      * The file is removed only when `append = false`, since the sink truncated or created it. An appending sink leaves the file in place,
      * because removing it would discard content the sink never wrote.
      */
    private def writeWith[S](path: Path, append: Boolean, createFolders: Boolean)(
        body: Path.WriteHandle => Unit < (Sync & Abort[FileWriteException] & S)
    )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
        Scope
            .acquireRelease(
                Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append = append, createFolders = createFolders)))
            )(handle => Sync.Unsafe.defer(handle.close()))
            .map { handle =>
                Abort.run[FileWriteException](body(handle)).map {
                    case Result.Failure(e) =>
                        if append then Abort.fail(e)
                        else path.remove.andThen(Abort.fail(e))
                    case ok => Abort.get(ok)
                }
            }

    extension [S](stream: Stream[Byte, S])
        /** Writes each byte of the stream to `path`, truncating an existing file and creating parent directories as needed. */
        def writeTo(path: Path)(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeTo(path, append = false, createFolders = true)

        /** Writes each byte of the stream to `path`, creating parent directories as needed. */
        def writeTo(path: Path, append: Boolean)(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeTo(path, append, createFolders = true)

        /** Writes each byte of the stream to `path`.
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error, unless `append = true`.
          *
          * Scala allows default arguments on only one alternative of an overloaded method, and the `Stream[String, S]` sink below owns them,
          * so this sink states the two options as overloads instead.
          *
          * @param path
          *   the file to write to
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param createFolders
          *   when `true`, creates the parent directories of `path` as needed
          */
        def writeTo(path: Path, append: Boolean, createFolders: Boolean)(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeWith(path, append, createFolders) { handle =>
                stream.foreachChunk { chunk =>
                    Sync.Unsafe.defer(Abort.get(handle.writeBytes(chunk)))
                }
            }
    end extension

    extension [S](stream: Stream[String, S])
        /** Writes each string chunk of the stream to `path` using the given charset (default UTF-8).
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error, unless `append = true`.
          *
          * @param path
          *   the file to write to
          * @param charset
          *   the charset used to encode each element
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param createFolders
          *   when `true` (the default), creates the parent directories of `path` as needed
          */
        def writeTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8,
            append: Boolean = false,
            createFolders: Boolean = true
        )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            writeWith(path, append, createFolders) { handle =>
                stream.foreach { s =>
                    Sync.Unsafe.defer(Abort.get(handle.writeString(s, charset)))
                }
            }

        /** Writes each string element as a separate line to `path` using the given charset.
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error, unless `append = true`.
          *
          * @param path
          *   the file to write to
          * @param charset
          *   the charset used to encode each line
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param createFolders
          *   when `true` (the default), creates the parent directories of `path` as needed
          */
        def writeLinesTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8,
            append: Boolean = false,
            createFolders: Boolean = true
        )(using Frame): Unit < (Scope & Sync & Abort[FileException] & S) =
            System.lineSeparator.map { sep =>
                writeWith(path, append, createFolders) { handle =>
                    stream.foreach { s =>
                        Sync.Unsafe.defer(Abort.get(handle.writeString(s + sep, charset)))
                    }
                }
            }
    end extension

end StreamSystemExtensions

export StreamSystemExtensions.*
