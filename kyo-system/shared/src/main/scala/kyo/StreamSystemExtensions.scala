package kyo

/** Stream sinks that write byte and text streams to the file system.
  *
  * These live in kyo-system rather than kyo-core because they are typed in terms of [[kyo.Path]] and [[kyo.FileWriteException]]. Keeping
  * them in kyo-core would make kyo-core depend on kyo-system, which already depends on kyo-core.
  *
  * @see
  *   [[kyo.Path]] for the path type these sinks target
  */
object StreamSystemExtensions:

    /** Shared write logic: opens a write handle via Scope, runs the body, and marks the handle finished.
      *
      * A body that fails never reaches `finish()`, so the handle's close removes what was half written. An appending sink is the exception
      * and finishes anyway: the entry already held content the sink never wrote, and close would take that content along with the partial
      * append.
      */
    private def writeWith[S](path: Path, append: Boolean, options: Path.WriteOptions)(
        body: Path.WriteHandle => Unit < (Sync & Abort[FileWriteException] & S)
    )(using Frame): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
        Scope
            .acquireRelease(
                Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append, options)))
            )(handle => Sync.Unsafe.defer(handle.close())) // Unsafe: closes the write handle at Scope exit
            .map { handle =>
                Abort.run[FileWriteException](body(handle)).map {
                    case Result.Failure(e) =>
                        if append then Sync.Unsafe.defer(handle.finish()).andThen(Abort.fail(e)) // Unsafe: keeps the pre-existing content
                        else Abort.fail(e)
                    case ok =>
                        Sync.Unsafe.defer(handle.finish()).andThen(Abort.get(ok)) // Unsafe: marks the write handle complete
                }
            }

    extension [S](stream: Stream[Byte, S])
        /** Writes each byte of the stream to `path`, truncating an existing file and creating parent directories as needed. */
        def writeTo(path: Path)(using Frame): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
            writeTo(path, append = false, Path.WriteOptions())

        /** Writes each byte of the stream to `path`, creating parent directories as needed. */
        def writeTo(path: Path, append: Boolean)(using Frame): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
            writeTo(path, append, Path.WriteOptions())

        /** Writes each byte of the stream to `path`.
          *
          * The write handle is acquired in a `Scope` and closed when the stream completes or fails. If the stream fails, the
          * partially-written file is removed before re-raising the error, unless `append = true`.
          *
          * Scala allows default arguments on only one alternative of an overloaded method, and the `Stream[String, S]` sink below owns them,
          * so this sink states the two options as overloads instead.
          *
          * @param path
          *   the file to write to
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param options
          *   the write policy, which carries whether the parent directories of `path` are created as needed
          */
        def writeTo(path: Path, append: Boolean, options: Path.WriteOptions)(using
            Frame
        ): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
            writeWith(path, append, options) { handle =>
                stream.foreachChunk { chunk =>
                    Sync.Unsafe.defer(Abort.get(handle.writeBytes(chunk))) // Unsafe: bridges a write-handle chunk write into the Sync tier
                }
            }
    end extension

    extension [S](stream: Stream[String, S])
        /** Writes each string chunk of the stream to `path` using the given charset (default UTF-8).
          *
          * The write handle is acquired in a `Scope` and closed when the stream completes or fails. If the stream fails, the
          * partially-written file is removed before re-raising the error, unless `append = true`.
          *
          * @param path
          *   the file to write to
          * @param charset
          *   the charset used to encode each element
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param options
          *   the write policy, which carries whether the parent directories of `path` are created as needed
          */
        def writeTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8,
            append: Boolean = false,
            options: Path.WriteOptions = Path.WriteOptions()
        )(using Frame): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
            writeWith(path, append, options) { handle =>
                stream.foreach { s =>
                    Sync.Unsafe.defer(Abort.get(handle.writeString(s, charset))) // Unsafe: bridges a write-handle string write into Sync
                }
            }

        /** Writes each string element as a separate line to `path` using the given charset.
          *
          * The write handle is acquired in a `Scope` and closed when the stream completes or fails. If the stream fails, the
          * partially-written file is removed before re-raising the error, unless `append = true`. The line separator is the one `System`
          * reports: `\n` on JS and the host separator on JVM and Native.
          *
          * @param path
          *   the file to write to
          * @param charset
          *   the charset used to encode each line
          * @param append
          *   when `true`, adds to the end of an existing file instead of truncating it
          * @param options
          *   the write policy, which carries whether the parent directories of `path` are created as needed
          */
        def writeLinesTo(
            path: Path,
            charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8,
            append: Boolean = false,
            options: Path.WriteOptions = Path.WriteOptions()
        )(using Frame): Unit < (Scope & Sync & Abort[FileWriteException] & S) =
            System.lineSeparator.map { sep =>
                writeWith(path, append, options) { handle =>
                    stream.foreach { s =>
                        Sync.Unsafe.defer(Abort.get(handle.writeString(s + sep, charset))) // Unsafe: bridges the write into the Sync tier
                    }
                }
            }
    end extension

end StreamSystemExtensions

// Exported by name. A wildcard emits one forwarder per member in an order the compiler does not fix, so two clean builds of identical
// sources produce different artifacts.
export StreamSystemExtensions.writeLinesTo
export StreamSystemExtensions.writeTo
