package kyo

import kyo.kernel.ArrowEffect

/** Stream extensions that write byte and text streams to the file system.
  *
  * These sinks acquire a write handle via a capability op in a `Scope`, stream the content, and remove the
  * partially-written file on failure (the handle's `finish()` is never reached on failure, triggering
  * delete-on-close). They live in kyo-system alongside [[kyo.Path]] because they couple to the file
  * capability; kyo-core keeps the platform-neutral stream combinators.
  *
  * @see
  *   [[kyo.Path]] for the file-path capability the sinks target
  */
object StreamFileExtensions:

    extension [S](stream: Stream[Byte, S])
        /** Writes each byte of the stream to `path`, creating parent directories as needed. The write
          * handle is acquired in a `Scope` and closed when the stream completes or fails; on failure the
          * partially-written file is removed (the handle's `finish()` is never reached).
          */
        def writeTo(path: Path)(using Frame): Unit < (Scope & PathWrite & Sync & S) =
            Scope.acquireRelease(
                ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append = false, createFolders = true))
            )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended write handle at Scope exit
                stream.foreachChunk { chunk =>
                    ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteChunk(handle, chunk))
                }.andThen(Sync.Unsafe.defer(handle.finish())) // Unsafe: marks the vended write handle complete
            }
    end extension

    extension [S](stream: Stream[String, S])
        /** Writes each string chunk of the stream to `path` using the given charset (default UTF-8).
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails.
          */
        def writeTo(path: Path, charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8)(using
            Frame
        ): Unit < (Scope & PathWrite & Sync & S) =
            Scope.acquireRelease(
                ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append = false, createFolders = true))
            )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended write handle at Scope exit
                stream.foreach { s =>
                    ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, s, charset))
                }.andThen(Sync.Unsafe.defer(handle.finish())) // Unsafe: marks the vended write handle complete
            }

        /** Writes each string element as a separate line to `path` using the given charset.
          *
          * The write channel is acquired in a `Scope` and released when the stream completes or fails. If the stream fails, the
          * partially-written file is deleted before re-raising the error. The line separator is
          * `java.lang.System.lineSeparator()`: `\n` on JS (Scala.js shim) and the host OS separator on JVM and Native.
          */
        def writeLinesTo(path: Path, charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8)(using
            Frame
        ): Unit < (Scope & PathWrite & Sync & S) =
            Sync.defer(java.lang.System.lineSeparator()).map { sep =>
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathWrite], Path.Op.OpenWrite(path, append = false, createFolders = true))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended write handle at Scope exit
                    stream.foreach { s =>
                        ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteString(handle, s + sep, charset))
                    }.andThen(Sync.Unsafe.defer(handle.finish())) // Unsafe: marks the vended write handle complete
                }
            }
    end extension
end StreamFileExtensions

export StreamFileExtensions.*
