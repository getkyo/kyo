package kyo

import java.nio.charset.Charset

/** Filesystem backend capabilities, effect-polymorphic in the backend effect `S`. [[Read]] exposes
  * inspection and content reads. [[Write]] extends it with mutation and structure operations.
  * Each public operation records the applicable typed failure category in its effect row and
  * accepts a call-site [[Frame]].
  *
  * @see [[Path.runReadOnlyWith]] for installing a read capability
  * @see [[Path.runWith]] for installing a write capability
  */
object FileSystem:

    trait Read[S]:
        def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < S

        // inspection
        def exists(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (S & Abort[FileReadException])
        def isDirectory(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def isRegularFile(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def isSymbolicLink(path: Path)(using Frame): Boolean < (S & Abort[FileReadException])
        def realPath(path: Path)(using
            Frame
        ): Path < (S & Abort[
            FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException
        ])

        /** Resolves the longest existing prefix of `path` and re-appends the segments below it.
          *
          * Unlike [[realPath]] this does not fail when `path` is absent. A path that does not exist
          * yet still has a determined location: the links on the part of it that does exist fix
          * where it will land. A path with no existing ancestor resolves to itself.
          *
          * This exists for callers that need the canonical location of a path they are about to
          * create: raising on the common case of "the target does not exist yet" would make the
          * method unusable for exactly the callers who need it, so absence is answered rather than
          * raised.
          *
          * The default resolves the deepest ancestor [[realPath]] accepts.
          */
        def realPathPrefix(path: Path)(using
            Frame
        ): Path < (S & Abort[
            FileInvalidPathException | FileAccessDeniedException | FileIOException
        ]) =
            Abort.run[FileNotFoundException](realPath(path)).map {
                case Result.Success(resolved) => resolved
                case _ =>
                    (path.parent, path.name) match
                        case (Present(parent), Present(name)) => realPathPrefix(parent).map(resolved => resolved / name)
                        case _                                => path
            }

        // read
        def read(path: Path)(using Frame): String < (S & Abort[FileReadException])
        def read(path: Path, charset: Charset)(using Frame): String < (S & Abort[FileReadException])
        def readBytes(path: Path)(using Frame): Span[Byte] < (S & Abort[FileReadException])
        def readLines(path: Path)(using Frame): Chunk[String] < (S & Abort[FileReadException])
        def readLines(path: Path, charset: Charset)(using Frame): Chunk[String] < (S & Abort[FileReadException])
        def size(path: Path)(using Frame): Long < (S & Abort[FileReadException])
        def stat(path: Path)(using Frame): Path.PathStat < (S & Abort[FileReadException])

        // read handles (internal handle types; back the streaming reads and walk)
        def openRead(path: Path)(using Frame): Path.ReadHandle < (S & Abort[FileReadException])
        def openReadLines(path: Path, charset: Charset)(using Frame): Path.LineReadHandle < (S & Abort[FileReadException])
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
            Frame
        ): Path.WalkHandle < (S & Abort[FileReadException | FileStructureException])
        def list(path: Path)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException])
        def list(path: Path, glob: Glob)(using Frame): Chunk[Path] < (S & Abort[FileReadException | FileStructureException]) =
            defaultCaseSensitivity.map(list(path, glob, _))
        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (S & Abort[FileReadException | FileStructureException])
    end Read

    trait Write[S] extends Read[S]:

        // write
        def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def writeBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def writeLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using Frame): Unit < (S & Abort[FileWriteException])
        def append(path: Path, value: String, options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def appendBytes(path: Path, value: Span[Byte], options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def appendLines(path: Path, value: Chunk[String], options: Path.WriteOptions)(using
            Frame
        ): Unit < (S & Abort[FileReadException | FileWriteException])
        def truncate(path: Path, size: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException])
        def setLastModified(path: Path, epochMs: Long)(using Frame): Unit < (S & Abort[FileReadException | FileWriteException])

        // write handle
        def openWrite(path: Path, append: Boolean, options: Path.WriteOptions)(using
            Frame
        ): Path.WriteHandle < (S & Abort[FileReadException | FileWriteException])
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])(using Frame): Unit < (S & Abort[FileWriteException])
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset)(using Frame): Unit < (S & Abort[FileWriteException])

        // directory / structure
        def mkDir(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])
        def mkFile(path: Path)(using Frame): Unit < (S & Abort[FileWriteException | FileStructureException])
        def move(
            from: Path,
            to: Path,
            options: Path.MoveOptions
        )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])
        def copy(
            from: Path,
            to: Path,
            options: Path.CopyOptions
        )(using Frame): Unit < (S & Abort[FileReadException | FileWriteException | FileStructureException])
        def remove(path: Path)(using Frame): Boolean < (S & Abort[FileReadException | FileStructureException])
        def removeExisting(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])
        def removeAll(path: Path)(using Frame): Unit < (S & Abort[FileReadException | FileStructureException])

        // scoped temp: vends a service-correct removal handle so cleanup runs through the creating service
        def tempDir(prefix: String)(using Frame): Path.TempDirHandle < (S & Abort[FileStructureException])

    end Write

    private val local = Local.init[FileSystem.Write[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Write[Any]]
    )

    private val readLocal = Local.init[FileSystem.Read[Any]](
        FileSystem.host.asInstanceOf[FileSystem.Read[Any]]
    )

    /** Runs `value` with `fileSystem` selected as the backend used by [[Path.run]] and
      * [[Path.runReadOnly]]. The selection is inherited by child fibers and restored when the
      * dynamic scope exits.
      *
      * @tparam FS the backend's own effect, preserved in the result row
      */
    def let[A, S, FS](fileSystem: FileSystem.Write[FS])(value: A < S)(using Frame): A < (FS & S) =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)
        )

    private[kyo] def useErased[A, S](f: FileSystem.Write[Any] => A < S)(using Frame): A < S =
        local.use(f)

    private[kyo] def useReadErased[A, S](f: FileSystem.Read[Any] => A < S)(using Frame): A < S =
        readLocal.use(f)

    private[kyo] def letErased[A, S, FS](fileSystem: FileSystem.Write[FS])(value: A < S)(using Frame): A < S =
        local.let(fileSystem.asInstanceOf[FileSystem.Write[Any]])(
            readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)
        )

    private[kyo] def letReadErased[A, S, FS](fileSystem: FileSystem.Read[FS])(value: A < S)(using Frame): A < S =
        readLocal.let(fileSystem.asInstanceOf[FileSystem.Read[Any]])(value)

    /** Default host backend: delegates every op to [[Path.Unsafe]], translating the concrete
      * `Result[File*Exception, A]` into `Abort[FileSystemException]`, so it preserves current
      * `Path` behavior exactly.
      */
    def host: FileSystem.Write[Sync] = HostFileSystem()

end FileSystem
