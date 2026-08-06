package kyo

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.PathPlatformSpecific

/** A cross-platform, immutable file-system path with effect-tracked I/O.
  *
  * Path provides a unified API for file operations across JVM, Scala.js (Node.js), and Scala Native. Every I/O operation is tracked in the
  * type system: reads carry `Abort[FileReadException]`, writes carry `Abort[FileWriteException]`, and directory mutations carry
  * `Abort[FileFsException]`. This means the compiler enforces that callers handle (or propagate) every possible failure mode.
  *
  * Paths are constructed via the `/` operator or the `apply` factory:
  *
  * {{{
  * val config = Path / "etc" / "app" / "config.toml"
  * val data   = Path("var", "data", "app")
  *
  * // Read with typed error handling
  * val content: String < (Sync & Abort[FileReadException]) = config.read
  *
  * // Streaming reads are Scope-managed (file handle auto-closed)
  * val lines: Stream[String, Scope & Sync & Abort[FileReadException]] = config.readLinesStream
  * }}}
  *
  * Inspection methods (`exists`, `isDirectory`, `isRegularFile`, `isSymbolicLink`) return `false` for inaccessible paths rather than
  * failing — they require only `Sync`, not `Abort`.
  *
  * **Streaming operations** (`readStream`, `readBytesStream`, `readLinesStream`, `walk`, `tail`) return `Stream` values that carry
  * `Scope` in their effect type. The underlying OS resource (file handle, directory handle) is acquired when the stream starts and released
  * when the enclosing `Scope` closes — whether by normal completion, error, or cancellation.
  *
  * @see
  *   [[FileException]] for the typed error hierarchy
  * @see
  *   [[kyo.Path.Unsafe]] for the abstract platform-specific implementation class
  */
opaque type Path = Path.Unsafe

object Path extends PathPlatformSpecific:

    given CanEqual[Path, Path] = CanEqual.derived

    /** A path segment, either a literal string or another Path whose parts are spliced in. */
    type Part = String | Path

    /** Combined file-attribute snapshot returned by [[Path.stat]].
      *
      * `lastModifiedMs` is the file's last-modified time in milliseconds since the Unix epoch.
      * `sizeBytes` is the file's size in bytes for regular files; the value for directories
      * and special files is platform-defined (typically 0 or the directory entry size).
      *
      * Returning both fields from a single underlying syscall guarantees the two values
      * reflect a consistent measurement of the file at one instant.
      */
    final case class PathStat(lastModifiedMs: Long, sizeBytes: Long) derives CanEqual

    /** Platform separator between path entries in classpath-style joined strings.
      *
      * Returns `":"` on Unix-family systems and `";"` on Windows. On Scala.js, forwards Node's `path.delimiter`.
      * Runtime-invariant; computed once at companion init.
      */
    val pathSeparator: String = platformPathSeparator

    /** Platform separator between segments of a single path.
      *
      * Returns `"/"` on Unix-family systems and `"\\"` on Windows. On Scala.js, forwards Node's `path.sep`.
      * Runtime-invariant; computed once at companion init.
      */
    val fileSeparator: String = platformFileSeparator

    // --- Construction ---

    /** Creates a Path from zero or more string-or-Path segments.
      *
      * Empty strings are dropped and `.`/`..` components are normalised by the platform implementation.
      *
      * {{{
      * val p = Path("usr", "local", "bin")
      * }}}
      */
    def apply(parts: Part*): Path =
        make(flattenParts(parts))

    /** Creates a path from a single segment (enables `Path / "a" / "b"` syntax starting from the companion). */
    infix def /(part: Path.Part)(using Frame): Path =
        make(flattenParts(Seq(part)))

    // --- Safe extension methods ---

    extension (self: Path)

        /** Returns the individual string components that make up this path. */
        def parts: Chunk[String] = self.parts

        /** Returns the final component of this path (the file or directory name).
          *
          * Returns `Absent` for a root or empty path.
          */
        def name: Maybe[String] =
            self.parts.lastMaybe match
                case Present(s) if s.nonEmpty => Present(s)
                case _                        => Absent

        /** Returns the parent path, or `Absent` if this is a root or single-component path. */
        def parent: Maybe[Path] =
            val ps = self.parts
            if ps.isEmpty || ps.size == 1 then Absent
            else Present(Path(ps.init*))
        end parent

        /** Lazily yields self, its parent, its grandparent, ..., up to and including the filesystem root.
          *
          * Use with `Stream.find` for "first ancestor where X" lookups (e.g., finding a project root
          * marker like `.git` or `build.sbt`). The stream is pure: it does not stat anything on disk.
          */
        def ancestors(using tag: Tag[Emit[Chunk[Path]]], frame: Frame): Stream[Path, Any] =
            @scala.annotation.tailrec
            def loop(cur: Path, acc: Chunk[Path]): Chunk[Path] =
                val next = acc.append(cur)
                cur.parent match
                    case Maybe.Present(parent) => loop(parent, next)
                    case Maybe.Absent          => next
            end loop
            Stream.init(loop(self, Chunk.empty))
        end ancestors

        /** Returns `true` if this path is absolute (begins at a filesystem root).
          *
          * Absolute paths are normalised to start with a leading `""` segment.
          */
        def isAbsolute: Boolean = self.isAbsolute

        /** Returns the file extension including the leading dot (e.g. `".gz"`), or `Absent` if none.
          *
          * A leading dot in the filename (dotfiles like `.gitignore`) is not treated as an extension.
          */
        def extName: Maybe[String] =
            self.parts.lastMaybe match
                case Absent => Absent
                case Present(name) =>
                    val dot = name.lastIndexOf('.')
                    if dot <= 0 then Absent else Present(name.substring(dot))

        /** Appends a single segment to this path. */
        infix def /(part: Path.Part)(using Frame): Path =
            make(self.parts ++ flattenParts(Seq(part)))

        // --- Inspection ---

        /** Returns `true` if this path exists in the file system (following symbolic links). */
        def exists(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.exists())

        /** Returns `true` if this path exists, optionally following symbolic links. */
        def exists(followLinks: Boolean)(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.exists(followLinks))

        /** Returns `true` if this path is a directory. */
        def isDirectory(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.isDirectory())

        /** Returns `true` if this path is a regular file. */
        def isRegularFile(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.isRegularFile())

        /** Returns `true` if this path is a symbolic link. */
        def isSymbolicLink(using Frame): Boolean < Sync =
            Sync.Unsafe.defer(self.unsafe.isSymbolicLink())

        /** Returns the canonical absolute path with every symbolic link in the chain resolved.
          *
          * Fails with `FileNotFoundException` if any element of the path does not exist, or
          * `FileAccessDeniedException` if the filesystem denies access. Useful for safe
          * path-under-root validation: compare `path.realPath` against `root.realPath` instead
          * of relying on syntactic checks (which miss symlinks that point outside the root).
          */
        def realPath(using Frame): Path < (Sync & Abort[FileException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.realPath()))

        /** Returns this path resolved to its canonical real path, but only if that real path is contained
          * within `root` (after resolving `root`'s own symlinks).
          *
          * The check follows every symbolic link in both `self` and `root`, so a symlink inside `root`
          * pointing outside is rejected. The pure path-prefix comparison runs against the canonical parts
          * of both paths; a path equal to `root` is considered contained.
          *
          * Both `self` and `root` must exist; if either does not, fails with `FileNotFoundException`.
          * If `self`'s real path is outside `root`'s real path, fails with `FileAccessDeniedException`
          * carrying the offending real path.
          *
          * Useful for any tool that exposes a configured root and accepts user-supplied relative paths:
          * call `(root / userInput).confinedTo(root)` to obtain a path that is statically known to live
          * under the root, defending against symlink escapes.
          */
        def confinedTo(root: Path)(using Frame): Path < (Sync & Abort[FileException]) =
            Sync.Unsafe.defer {
                Abort.get(root.unsafe.realPath()).map { rootReal =>
                    Abort.get(self.unsafe.realPath()).map { selfReal =>
                        if selfReal.parts.take(rootReal.parts.size) == rootReal.parts then (selfReal: Path < Abort[FileException])
                        else Abort.fail[FileException](FileAccessDeniedException(selfReal))
                    }
                }
            }

        // --- Read ---

        /** Reads the entire file contents as a UTF-8 string. */
        def read(using Frame): String < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.read()))

        /** Reads the entire file contents using the given charset. */
        def read(charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.read(charset)))

        /** Reads the entire file contents as a `Span[Byte]`. */
        def readBytes(using Frame): Span[Byte] < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.readBytes()))

        /** Returns the size in bytes of the regular file at this path.
          *
          * Fails with `FileReadException` if the path does not exist, is not a regular file, or the underlying read fails.
          */
        def size(using Frame): Long < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.size()))

        /** Returns mtime and size atomically from a single underlying syscall.
          *
          * Fails with `FileReadException` if the path does not exist, is not readable, or the underlying call fails.
          *
          * Prefer this over separate `lastModified` + `size` reads when both are needed:
          * a single syscall guarantees the two values reflect the same instant.
          */
        def stat(using Frame): PathStat < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.stat()))

        /** Reads all lines from the file as a `Chunk[String]` (UTF-8). */
        def readLines(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.readLines()))

        /** Reads all lines from the file as a `Chunk[String]` using the given charset. */
        def readLines(charset: Charset)(using Frame): Chunk[String] < (Sync & Abort[FileReadException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.readLines(charset)))

        /** Streams the file contents as UTF-8 decoded strings (chunked by the platform buffer size). */
        def readStream(using Frame): Stream[String, Scope & Sync & Abort[FileReadException]] =
            readStream(StandardCharsets.UTF_8)

        /** Streams the file contents as decoded strings using the given charset. */
        def readStream(charset: Charset)(using Frame): Stream[String, Scope & Sync & Abort[FileReadException]] =
            readStream(charset, 8192)

        /** Streams the file contents as decoded strings using the given charset and read buffer size. */
        def readStream(charset: Charset, bufferSize: Int)(using Frame): Stream[String, Scope & Sync & Abort[FileReadException]] =
            Stream {
                Scope.acquireRelease(
                    Sync.Unsafe.defer(Abort.get(self.unsafe.openRead()))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle =>
                    val rawBuf      = new Array[Byte](bufferSize)
                    val decoder     = charset.newDecoder()
                    val maxTrailing = math.ceil(charset.newEncoder().maxBytesPerChar()).toInt
                    val inBuf =
                        java.nio.ByteBuffer.allocate(bufferSize + maxTrailing) // extra space for incomplete trailing multi-byte sequence
                    val outBuf = java.nio.CharBuffer.allocate(math.ceil(bufferSize * decoder.maxCharsPerByte()).toInt)
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            val result = handle.readChunk(rawBuf)
                            if result.isEof then
                                // End of file — flush any bytes still held in inBuf
                                inBuf.flip()
                                outBuf.clear()
                                decoder.decode(inBuf, outBuf, true)
                                decoder.flush(outBuf)
                                outBuf.flip()
                                if outBuf.hasRemaining then
                                    Emit.valueWith(Chunk(outBuf.toString))(Loop.done)
                                else Loop.done
                            else
                                // Append new bytes after any leftover bytes from the previous read
                                inBuf.put(rawBuf, 0, result.bytesRead)
                                inBuf.flip()
                                outBuf.clear()
                                // false = not end-of-input; decoder leaves incomplete trailing sequences in inBuf
                                decoder.decode(inBuf, outBuf, false)
                                inBuf.compact() // leftover incomplete bytes slide to position 0
                                outBuf.flip()
                                if outBuf.hasRemaining then
                                    Emit.valueWith(Chunk(outBuf.toString))(Loop.continue)
                                else Loop.continue
                            end if
                        }
                    }
                }
            }

        /** Streams the raw bytes of the file. */
        def readBytesStream(using Frame): Stream[Byte, Scope & Sync & Abort[FileReadException]] =
            readBytesStream(8192)

        /** Streams the raw bytes of the file using the given read buffer size. */
        def readBytesStream(bufferSize: Int)(using Frame): Stream[Byte, Scope & Sync & Abort[FileReadException]] =
            Stream {
                Scope.acquireRelease(
                    Sync.Unsafe.defer(Abort.get(self.unsafe.openRead()))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle =>
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            val buf    = new Array[Byte](bufferSize)
                            val result = handle.readChunk(buf)
                            if result.isEof then Loop.done
                            else if result.bytesRead == bufferSize then
                                Emit.valueWith(Chunk.fromNoCopy(buf))(Loop.continue)
                            else
                                Emit.valueWith(Chunk.fromNoCopy(java.util.Arrays.copyOf(buf, result.bytesRead)))(Loop.continue)
                            end if
                        }
                    }
                }
            }

        /** Streams the file line-by-line as UTF-8 strings. */
        def readLinesStream(using Frame): Stream[String, Scope & Sync & Abort[FileReadException]] =
            readLinesStream(StandardCharsets.UTF_8)

        /** Streams the file line-by-line using the given charset. */
        def readLinesStream(charset: Charset)(using Frame): Stream[String, Scope & Sync & Abort[FileReadException]] =
            Stream {
                Scope.acquireRelease(
                    Sync.Unsafe.defer(Abort.get(self.unsafe.openReadLines(charset)))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle =>
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            handle.readLine() match
                                case Absent        => Loop.done
                                case Present(line) => Emit.valueWith(Chunk(line))(Loop.continue)
                        }
                    }
                }
            }

        /** Tails the file, emitting new lines as they are appended. Uses a 100ms default poll delay. */
        def tail(using Frame): Stream[String, Async & Scope & Abort[FileReadException]] =
            tail(100.millis)

        /** Tails the file, emitting new lines as they are appended, sleeping `pollDelay` between polls. */
        def tail(pollDelay: Duration)(using Frame): Stream[String, Async & Scope & Abort[FileReadException]] =
            tail(pollDelay, 8192)

        /** Tails the file, emitting new lines as they are appended, sleeping `pollDelay` between polls, using the given read buffer size.
          */
        def tail(pollDelay: Duration, bufferSize: Int)(using Frame): Stream[String, Async & Scope & Abort[FileReadException]] =
            Stream {
                Scope.acquireRelease(
                    Sync.Unsafe.defer(Abort.get(self.unsafe.openRead()))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle =>
                    // Seek to end first, then poll for new content
                    Sync.Unsafe.defer {
                        Abort.get(self.unsafe.size()).map { fileSize =>
                            Sync.Unsafe.defer {
                                handle.position(fileSize)
                                val buf = new Array[Byte](bufferSize)
                                // State: (file position, leftover bytes from incomplete UTF-8, pending incomplete line text)
                                val emptyBytes = new Array[Byte](0)
                                Loop((fileSize, emptyBytes, "")) { case (pos, leftover, pending) =>
                                    Sync.Unsafe.defer {
                                        val result = handle.readChunk(buf)
                                        if result.isEof then
                                            Sync.Unsafe.defer {
                                                Abort.get(self.unsafe.size()).map { currentSize =>
                                                    if currentSize < pos then
                                                        // File was truncated — reset to beginning
                                                        Sync.Unsafe.defer(handle.position(0L))
                                                            .andThen(Loop.continue((0L, emptyBytes, "")))
                                                    else
                                                        Async.sleep(pollDelay)
                                                            .andThen(Loop.continue((pos, leftover, pending)))
                                                }
                                            }
                                        else
                                            val n = result.bytesRead
                                            // Combine leftover bytes from previous read with new bytes
                                            val allBytes =
                                                if leftover.isEmpty then java.util.Arrays.copyOf(buf, n)
                                                else
                                                    val combined = new Array[Byte](leftover.length + n)
                                                    java.lang.System.arraycopy(leftover, 0, combined, 0, leftover.length)
                                                    java.lang.System.arraycopy(buf, 0, combined, leftover.length, n)
                                                    combined
                                            // Find how many trailing bytes form an incomplete UTF-8 sequence
                                            val incomplete = incompleteUtf8Tail(allBytes, allBytes.length)
                                            val decodeLen  = allBytes.length - incomplete
                                            val newLeftover =
                                                if incomplete > 0 then java.util.Arrays.copyOfRange(allBytes, decodeLen, allBytes.length)
                                                else emptyBytes
                                            val text  = pending + new String(allBytes, 0, decodeLen, StandardCharsets.UTF_8)
                                            val parts = text.split("\r?\n", -1).toList
                                            val (toEmit, newPending) =
                                                if text.endsWith("\n") then (parts.dropRight(1), "")
                                                else (parts.dropRight(1), parts.last)
                                            if toEmit.isEmpty then Loop.continue((pos + n, newLeftover, newPending))
                                            else Emit.valueWith(Chunk.from(toEmit))(Loop.continue((pos + n, newLeftover, newPending)))
                                        end if
                                    }
                                }
                            }
                        }
                    }
                }
            }

        // --- Write ---

        /** Writes `value` to the file, creating parent directories when `createFolders = true` (the default). */
        def write(value: String, createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.write(value, createFolders)))

        /** Writes raw bytes to the file. */
        def writeBytes(value: Span[Byte], createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.writeBytes(value, createFolders)))

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def writeLines(value: Chunk[String], createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.writeLines(value, createFolders)))

        /** Appends `value` to the file, creating parent directories when `createFolders = true`. */
        def append(value: String, createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.append(value, createFolders)))

        /** Appends raw bytes to the file. */
        def appendBytes(value: Span[Byte], createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.appendBytes(value, createFolders)))

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def appendLines(value: Chunk[String], createFolders: Boolean = true)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.appendLines(value, createFolders)))

        /** Truncates the file to at most `size` bytes. */
        def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.truncate(size)))

        /** Sets the last-modified time of the file to `epochMs` milliseconds since the Unix epoch.
          *
          * Fails with `FileWriteException` if the path does not exist or the operation is not permitted.
          */
        def setLastModified(epochMs: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.setLastModified(epochMs)))

        // --- Directory / structure ---

        /** Creates this path as a directory (including all missing parent directories). */
        def mkDir(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.mkDir()))

        /** Creates this path as an empty file (parent directories created if missing). */
        def mkFile(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.mkFile()))

        /** Lists all direct children of this directory. */
        def list(using Frame): Chunk[Path] < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.list()))

        /** Lists direct children of this directory whose names match `glob`. */
        def list(glob: String)(using Frame): Chunk[Path] < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.list(glob)))

        /** Streams all entries under this directory tree (unlimited depth, not following links). */
        def walk(using Frame): Stream[Path, Sync & Scope & Abort[FileFsException]] =
            walk(Int.MaxValue, followLinks = false)

        /** Streams all entries under this directory tree up to `maxDepth`, optionally following symbolic links. */
        def walk(maxDepth: Int = Int.MaxValue, followLinks: Boolean = false)(using
            Frame
        ): Stream[Path, Sync & Scope & Abort[FileFsException]] =
            Stream {
                Scope.acquireRelease(
                    Sync.Unsafe.defer(Abort.get(self.unsafe.openWalk(maxDepth, followLinks)))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle =>
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            handle.next() match
                                case Absent        => Loop.done
                                case Present(path) => Emit.valueWith(Chunk(path))(Loop.continue)
                        }
                    }
                }
            }

        /** Moves this path to `to`. */
        def move(
            to: Path,
            replaceExisting: Boolean = false,
            atomicMove: Boolean = false,
            createFolders: Boolean = true
        )(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.move(to, replaceExisting, atomicMove, createFolders)))

        /** Copies this path to `to`. */
        def copy(
            to: Path,
            followLinks: Boolean = true,
            replaceExisting: Boolean = false,
            copyAttributes: Boolean = false,
            createFolders: Boolean = true
        )(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.copy(to, followLinks, replaceExisting, copyAttributes, createFolders)))

        /** Deletes this path if it exists. Returns `true` if it was deleted, `false` if it did not exist. */
        def remove(using Frame): Boolean < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.remove()))

        /** Deletes this path, raising `FileNotFoundException` if it does not exist. */
        def removeExisting(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.removeExisting()))

        /** Recursively deletes this path and all of its contents. */
        def removeAll(using Frame): Unit < (Sync & Abort[FileFsException]) =
            Sync.Unsafe.defer(Abort.get(self.unsafe.removeAll()))

        /** Returns the underlying `Unsafe` implementation for direct use in unsafe code. */
        def unsafe: Path.Unsafe = self

    end extension

    // --- System directories ---

    /** The current working directory of the JVM (JVM/Native) or Node process (JS).
      *
      * Reads at call time, so subsequent `process.chdir` (or test fixtures that fork with a
      * different working dir) take effect on the next access. Use with `path.ancestors` for
      * "find the project root containing X" style lookups.
      */
    def cwd(using Frame): Path < Sync = Sync.Unsafe.defer(cwdPath)

    /** Well-known base directories for the current OS (cache, config, data, etc.). */
    lazy val basePaths: BasePaths = platformBasePaths

    /** Well-known user directories (home, desktop, downloads, etc.). */
    lazy val userPaths: UserPaths = platformUserPaths

    /** Per-project directories derived from a `(qualifier, organization, application)` triple. */
    def projectPaths(qualifier: String, organization: String, application: String): ProjectPaths =
        platformProjectPaths(qualifier, organization, application)

    /** OS base directories. */
    case class BasePaths(
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        executable: Path,
        preference: Path,
        runtime: Path,
        tmp: Path
    ) derives CanEqual

    /** User home directories. */
    case class UserPaths(
        home: Path,
        audio: Path,
        desktop: Path,
        document: Path,
        download: Path,
        font: Path,
        picture: Path,
        public: Path,
        template: Path,
        video: Path
    ) derives CanEqual

    /** Per-application project directories. */
    case class ProjectPaths(
        path: Path,
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        preference: Path,
        runtime: Path
    ) derives CanEqual

    /** Returns the number of trailing bytes that form an incomplete UTF-8 sequence. */
    private def incompleteUtf8Tail(bytes: Array[Byte], len: Int): Int =
        // Scan backwards from the end for a leading byte (11xxxxxx or 0xxxxxxx)
        var i = len - 1
        // Skip continuation bytes (10xxxxxx)
        while i >= 0 && (bytes(i) & 0xc0) == 0x80 do i -= 1
        if i < 0 then 0 // all continuation bytes — shouldn't happen
        else
            val leading  = bytes(i)
            val startPos = i
            val tailLen  = len - startPos
            // Determine expected sequence length from leading byte
            val expected =
                if (leading & 0x80) == 0 then 1         // 0xxxxxxx — ASCII
                else if (leading & 0xe0) == 0xc0 then 2 // 110xxxxx
                else if (leading & 0xf0) == 0xe0 then 3 // 1110xxxx
                else if (leading & 0xf8) == 0xf0 then 4 // 11110xxx
                else 1                                  // invalid leading byte, treat as complete
            if tailLen < expected then tailLen else 0
        end if
    end incompleteUtf8Tail

    /** Flattens a sequence of `Part` values into a `Chunk[String]`. */
    private[kyo] def flattenParts(parts: Seq[Part]): Chunk[String] =
        Chunk.from(parts.flatMap {
            case s: String => s.split("[/\\\\]", -1).toSeq // Split on both / and \ for cross-platform support
            case p: Path   => p.parts.toSeq
        })

    // --- Abstract Unsafe class ---

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        // --- Pure accessors (no AllowUnsafe needed) ---

        /** The individual string components of this path. */
        def parts: Chunk[String]

        /** Human-readable string representation of this path. */
        def show: String

        /** Returns `true` if this path is absolute (begins at a filesystem root). */
        def isAbsolute: Boolean

        /** Returns the human-readable representation; delegates to `show` so Path values display correctly. */
        override def toString: String = show

        // --- Inspection ---

        def exists()(using AllowUnsafe): Boolean
        def exists(followLinks: Boolean)(using AllowUnsafe): Boolean
        def isDirectory()(using AllowUnsafe): Boolean
        def isRegularFile()(using AllowUnsafe): Boolean
        def isSymbolicLink()(using AllowUnsafe): Boolean
        def realPath()(using AllowUnsafe, Frame): Result[FileException, Path]

        // --- Read ---

        def read()(using AllowUnsafe, Frame): Result[FileReadException, String]
        def read(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, String]
        def readBytes()(using AllowUnsafe, Frame): Result[FileReadException, Span[Byte]]
        def readLines()(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]]
        def readLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]]

        // --- Streaming read handles (abstract — platform provides the concrete handles) ---

        def openRead()(using AllowUnsafe, Frame): Result[FileReadException, Path.ReadHandle]
        def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle]
        def size()(using AllowUnsafe, Frame): Result[FileReadException, Long]
        def stat()(using AllowUnsafe, Frame): Result[FileReadException, PathStat]

        // --- Write ---

        def write(value: String, createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def writeBytes(value: Span[Byte], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def writeLines(value: Chunk[String], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def append(value: String, createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def appendBytes(value: Span[Byte], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def appendLines(value: Chunk[String], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        // --- Directory / structure ---

        def list()(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]]
        def list(glob: String)(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]]
        def mkDir()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def mkFile()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def move(to: Path, replaceExisting: Boolean = false, atomicMove: Boolean = false, createFolders: Boolean = true)(using
            AllowUnsafe,
            Frame
        ): Result[FileFsException, Unit]
        def copy(
            to: Path,
            followLinks: Boolean = true,
            replaceExisting: Boolean = false,
            copyAttributes: Boolean = false,
            createFolders: Boolean = true
        )(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def remove()(using AllowUnsafe, Frame): Result[FileFsException, Boolean]
        def removeExisting()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def removeAll()(using AllowUnsafe, Frame): Result[FileFsException, Unit]

        // --- Walk handle (abstract — platform provides the resource management) ---

        def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileFsException, Path.WalkHandle]

        // --- Streaming write handles ---

        /** Opens a write handle for streaming byte or string output. The caller must close the handle via `Scope.acquireRelease`. */
        def openWrite(append: Boolean, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle]

        /** Lifts this `Unsafe` value back into the safe `Path` opaque type. */
        def safe: Path = this

    end Unsafe

    // --- WriteHandle — abstraction for open write channels ---

    /** An open write channel returned by `Path.Unsafe.openWrite`. Platform implementations provide the concrete class. */
    abstract private[kyo] class WriteHandle:
        /** Writes a chunk of bytes to the channel. */
        def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Writes a string to the channel using the given charset. */
        def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Closes the channel, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end WriteHandle

    // --- Read handles — returned by Path.Unsafe.openRead / openReadLines ---

    /** The result of a `ReadHandle.readChunk` call — either a positive byte count or EOF. */
    opaque type ReadResult = Int

    object ReadResult:
        /** End of file — no more data will be produced. */
        val Eof: ReadResult = -1

        /** Wraps a raw byte count (from `InputStream.read` or `FileChannel.read`) into a `ReadResult`. */
        def apply(n: Int): ReadResult = n

        extension (self: ReadResult)
            /** `true` when the stream has reached end-of-file. */
            def isEof: Boolean = self <= 0

            /** The number of bytes read, or 0 if EOF. */
            def bytesRead: Int = if self <= 0 then 0 else self
        end extension
    end ReadResult

    /** An open read channel returned by `Path.Unsafe.openRead`. Platform implementations provide the concrete class.
      *
      * A handle is a single-consumer stateful resource: it carries a positional read cursor and a retained scan
      * buffer, so it must never be shared across fibers or threads.
      */
    abstract private[kyo] class ReadHandle:
        /** Reads up to `buffer.length` bytes into `buffer`. Returns a `ReadResult` that is either `Eof` or a positive byte count. */
        def readChunk(buffer: Array[Byte])(using AllowUnsafe): ReadResult

        /** Sets the channel position to `offset` bytes from the start of the file. */
        def position(offset: Long)(using AllowUnsafe): Unit

        /** Reads the current content into the handle's own retained buffer and parses the first ASCII-decimal
          * `Long` in place, with no intermediate `String` and no `Maybe` box. TOTAL: returns
          * `ReadHandle.AbsentLong` (`Long.MinValue`) on empty content, on no leading digit after whitespace, or
          * on overflow. The parser recognizes only ASCII decimal digits (no sign), so it can never itself
          * produce a negative `Long`, which makes the sentinel collision-free by construction. Each call parses
          * from the start of the content; it does not advance a cursor across calls, and it does not disturb the
          * `readChunk` cursor: an interleaved `readChunk` resumes exactly where it left off.
          */
        def readLong()(using AllowUnsafe): Long

        /** Closes the channel, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end ReadHandle

    private[kyo] object ReadHandle:
        /** The sentinel `readLong` returns for empty, unparseable, or overflowing content (`Long.MinValue`).
          * A host metric `readLong` parses (bytes, nanoseconds, counts, pids) is non-negative, and the parser
          * accepts no leading sign, so this value can never collide with a genuine parse result. Peer of the
          * existing `Path.ReadResult.Eof` sentinel-on-companion pattern.
          */
        val AbsentLong: Long = Long.MinValue

        /** Parses the first maximal ASCII-decimal run in `buf[0, len)` as a `Long`, skipping leading ASCII
          * whitespace. Returns `AbsentLong` when no digit leads or the value overflows. Shared by the concrete
          * platform `readLong` implementations after each fills its own retained buffer.
          */
        private[kyo] def parseLeadingLong(buf: Array[Byte], len: Int): Long =
            @scala.annotation.tailrec
            def skip(i: Int): Int =
                if i < len && { val b = buf(i); b == ' ' || b == '\t' || b == '\n' || b == '\r' } then skip(i + 1)
                else i
            @scala.annotation.tailrec
            def digits(i: Int, acc: Long, any: Boolean): Long =
                if i >= len then (if any then acc else AbsentLong)
                else
                    val b = buf(i)
                    if b >= '0' && b <= '9' then
                        val d = (b - '0').toLong
                        if acc > (Long.MaxValue - d) / 10L then AbsentLong
                        else digits(i + 1, acc * 10L + d, true)
                    else if any then acc
                    else AbsentLong
                    end if
            val start = skip(0)
            if start >= len then AbsentLong else digits(start, 0L, false)
        end parseLeadingLong
    end ReadHandle

    /** An open buffered line reader returned by `Path.Unsafe.openReadLines`. Platform implementations provide the concrete class. */
    abstract private[kyo] class LineReadHandle:
        /** Reads the next line. Returns `Absent` at EOF. */
        def readLine()(using AllowUnsafe): Maybe[String]

        /** Closes the reader, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end LineReadHandle

    /** An open directory walker returned by `Path.Unsafe.openWalk`. Platform implementations provide the concrete class. */
    abstract private[kyo] class WalkHandle:
        /** Returns the next path in the walk, or `Absent` when exhausted. */
        def next()(using AllowUnsafe): Maybe[Path]

        /** Closes the walker, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end WalkHandle

end Path
