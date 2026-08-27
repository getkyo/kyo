package kyo

import kyo.kernel.ArrowEffect

/** Stream sinks that write byte and text streams to the file system.
  *
  * These live in kyo-system rather than kyo-core because they are typed in terms of [[kyo.Path]] and the write capability. Keeping them in
  * kyo-core would make kyo-core depend on kyo-system, which already depends on kyo-core.
  *
  * Every sink carries `PathWrite`, so it writes to whichever filesystem the enclosing runner installed and the write failure lands on that
  * runner's `Abort[FileSystemException]`.
  *
  * @see
  *   [[kyo.Path]] for the path type these sinks target
  */
object StreamSystemExtensions:

    /** Shared write logic: opens a write handle through the write capability, runs the body, and marks the handle finished.
      *
      * A body that fails never reaches `finish()`, so the handle's close removes what was half written. An appending sink is the exception
      * and finishes anyway: the entry already held content the sink never wrote, and close would take that content along with the partial
      * append.
      *
      * The failure is raised by the enclosing runner rather than by this frame, so there is no local `Abort` for an `Abort.run` to catch.
      * The append case is decided in the `Scope` finalizer instead, which is the one place that sees whether the computation finished or
      * failed. Finishing and closing in a single finalizer keeps that decision independent of the finalizer parallelism `Scope.run` was
      * given.
      */
    private def writeWith[S](path: Path, append: Boolean, options: Path.WriteOptions)(
        body: Path.WriteHandle => Unit < (PathWrite & Sync & S)
    )(using Frame): Unit < (Scope & PathWrite & Sync & S) =
        ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append, options)).map { handle =>
            Scope.ensure { outcome =>
                // Unsafe: finishes and closes the vended write handle at Scope exit.
                Sync.Unsafe.defer {
                    if outcome.nonEmpty && append then handle.finish() // keeps the content the entry already held
                    handle.close()
                }
            }
                .andThen(body(handle))
                .andThen(Sync.Unsafe.defer(handle.finish())) // Unsafe: marks the vended write handle complete
        }

    extension [S](stream: Stream[Byte, S])
        /** Writes each byte of the stream to `path`, truncating an existing file and creating parent directories as needed. */
        def writeTo(path: Path)(using Frame): Unit < (Scope & PathWrite & Sync & S) =
            writeTo(path, append = false, Path.WriteOptions())

        /** Writes each byte of the stream to `path`, creating parent directories as needed. */
        def writeTo(path: Path, append: Boolean)(using Frame): Unit < (Scope & PathWrite & Sync & S) =
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
        ): Unit < (Scope & PathWrite & Sync & S) =
            writeWith(path, append, options) { handle =>
                stream.foreachChunk { chunk =>
                    ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteChunk(handle, chunk))
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
        )(using Frame): Unit < (Scope & PathWrite & Sync & S) =
            writeWith(path, append, options) { handle =>
                stream.foreach { s =>
                    ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, s, charset))
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
        )(using Frame): Unit < (Scope & PathWrite & Sync & S) =
            System.lineSeparator.map { sep =>
                writeWith(path, append, options) { handle =>
                    stream.foreach { s =>
                        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, s + sep, charset))
                    }
                }
            }
    end extension

end StreamSystemExtensions

// Exported by name. A wildcard emits one forwarder per member in an order the compiler does not fix, so two clean builds of identical
// sources produce different artifacts.
export StreamSystemExtensions.writeLinesTo
export StreamSystemExtensions.writeTo
