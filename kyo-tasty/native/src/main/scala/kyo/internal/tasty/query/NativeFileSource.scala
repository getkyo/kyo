package kyo.internal.tasty.query

import kyo.*
import scala.scalanative.posix.sys.stat as posixStat
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Scala Native POSIX implementation of `FileSource`.
  *
  * Uses POSIX `open(2)` / `read(2)` / `close(2)` for file reading, `opendir(3)` / `readdir(3)` / `closedir(3)` for directory listing, and
  * `stat(2)` for metadata. Follows symlinks (uses `stat`, not `lstat`).
  *
  * Error handling: POSIX errors are mapped to `TastyError.FileNotFound` (for missing paths) or `TastyError.CorruptedFile` (for I/O errors).
  */
object NativeFileSource extends FileSource:

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                readFileNative(path)
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                writeFileNative(path, bytes)
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                renameNative(from, to)
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                mkdirsNative(path)
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else
            // JAR roots are not supported on Scala Native; only directory roots are walked.
            // A JAR path is treated as a plain path, which either doesn't exist or is not a directory,
            // and will return Chunk.empty from listDirNative.
            Sync.defer:
                try
                    listDirNativeMulti(dir, suffixes)
                catch
                    case ex: Throwable =>
                        Abort.fail(TastyError.FileNotFound(s"$dir: ${ex.getMessage}"))

    def exists(path: String)(using Frame): Boolean < Sync =
        Sync.defer:
            try
                statPathExists(path)
            catch
                case _: Throwable => false

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                statFile(path)
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))

    private def readFileNative(path: String): Array[Byte] =
        Zone:
            val fd = PosixFileBindings.open(toCString(path), 0) // O_RDONLY = 0
            if fd < 0 then throw new java.io.IOException(s"open failed for $path")
            try
                // Use posixlib fstat to get st_size from the canonical cross-platform struct.
                val statBuf = alloc[posixStat.stat]()
                if posixStat.fstat(fd, statBuf) < 0 then
                    throw new java.io.IOException(s"fstat failed for $path")
                val size = statBuf._6.toLong // _6 = st_size (off_t) in posixlib stat struct
                if size == 0 then Array.empty[Byte]
                else
                    val buf   = new Array[Byte](size.toInt)
                    var total = 0
                    while total < size do
                        val chunk = (size - total).min(65536).toInt
                        val result = Zone:
                            val tmp = alloc[Byte](chunk.toUInt)
                            val n   = PosixFileBindings.read(fd, tmp, chunk)
                            if n > 0 then
                                var i = 0
                                while i < n do
                                    buf(total + i) = tmp(i)
                                    i += 1
                            end if
                            n
                        if result <= 0 then throw new java.io.IOException(s"read failed for $path")
                        total += result
                    end while
                    buf
                end if
            finally
                val _ = PosixFileBindings.close(fd)
            end try

    private def listDirNative(dir: String, suffix: String): Chunk[String] =
        val results = scala.collection.mutable.ArrayBuffer.empty[String]
        Zone:
            val dirPtr = PosixFileBindings.opendir(toCString(dir))
            if dirPtr == null then throw new java.io.IOException(s"opendir failed for $dir")
            try
                var entry = PosixFileBindings.readdir(dirPtr)
                while entry != null do
                    val name = fromCString(PosixFileBindings.direntName(entry))
                    if name != "." && name != ".." then
                        val full = s"$dir/$name"
                        // Check if it's a file with the right suffix
                        val isFile = Zone:
                            val statBuf = alloc[PosixFileBindings.StatBuf]()
                            PosixFileBindings.stat(toCString(full), statBuf) == 0 &&
                            (statBuf._2 & 0xf000).toInt == 0x8000 // S_IFREG
                        if isFile && name.endsWith(suffix) then
                            results += full
                        else
                            // Check if it's a directory for recursion
                            val isDir = Zone:
                                val statBuf = alloc[PosixFileBindings.StatBuf]()
                                PosixFileBindings.stat(toCString(full), statBuf) == 0 &&
                                (statBuf._2 & 0xf000).toInt == 0x4000 // S_IFDIR
                            if isDir then
                                results ++= listDirNative(full, suffix).toSeq
                        end if
                    end if
                    entry = PosixFileBindings.readdir(dirPtr)
                end while
            finally
                val _ = PosixFileBindings.closedir(dirPtr)
            end try
        Chunk.from(results.toSeq)
    end listDirNative

    /** Multi-suffix variant: iterate the directory once, matching against any suffix in the chunk. */
    private def listDirNativeMulti(dir: String, suffixes: Chunk[String]): Chunk[String] =
        val results = scala.collection.mutable.ArrayBuffer.empty[String]
        Zone:
            val dirPtr = PosixFileBindings.opendir(toCString(dir))
            if dirPtr == null then throw new java.io.IOException(s"opendir failed for $dir")
            try
                var entry = PosixFileBindings.readdir(dirPtr)
                while entry != null do
                    val name = fromCString(PosixFileBindings.direntName(entry))
                    if name != "." && name != ".." then
                        val full = s"$dir/$name"
                        val isFile = Zone:
                            val statBuf = alloc[PosixFileBindings.StatBuf]()
                            PosixFileBindings.stat(toCString(full), statBuf) == 0 &&
                            (statBuf._2 & 0xf000).toInt == 0x8000 // S_IFREG
                        if isFile then
                            var i     = 0
                            var found = false
                            while i < suffixes.length && !found do
                                if name.endsWith(suffixes(i)) then
                                    results += full
                                    found = true
                                i += 1
                            end while
                        else
                            val isDir = Zone:
                                val statBuf = alloc[PosixFileBindings.StatBuf]()
                                PosixFileBindings.stat(toCString(full), statBuf) == 0 &&
                                (statBuf._2 & 0xf000).toInt == 0x4000 // S_IFDIR
                            if isDir then
                                results ++= listDirNativeMulti(full, suffixes).toSeq
                        end if
                    end if
                    entry = PosixFileBindings.readdir(dirPtr)
                end while
            finally
                val _ = PosixFileBindings.closedir(dirPtr)
            end try
        Chunk.from(results.toSeq)
    end listDirNativeMulti

    private def statPathExists(path: String): Boolean =
        Zone:
            val statBuf = alloc[posixStat.stat]()
            posixStat.stat(toCString(path), statBuf) == 0

    private def statFile(path: String): FileSource.FileStat =
        Zone:
            val statBuf = alloc[posixStat.stat]()
            if posixStat.stat(toCString(path), statBuf) != 0 then
                throw new java.io.IOException(s"stat failed for $path")
            val mtime = statBuf._8._1.toLong // _8 = st_mtim (timespec), ._1 = tv_sec in posixlib stat struct
            val size  = statBuf._6.toLong    // _6 = st_size (off_t) in posixlib stat struct
            FileSource.FileStat(mtime * 1000L, size)

    private def writeFileNative(path: String, bytes: Array[Byte]): Unit =
        // Use java.io.FileOutputStream which is available in Scala Native 0.5+ and handles
        // file creation, truncation, and permissions (0644) correctly without POSIX FFI.
        val fos = new java.io.FileOutputStream(path)
        try
            fos.write(bytes)
        finally
            fos.close()
        end try
    end writeFileNative

    private def renameNative(from: String, to: String): Unit =
        Zone:
            if PosixFileBindings.rename(toCString(from), toCString(to)) != 0 then
                throw new java.io.IOException(s"rename failed: $from -> $to")

    private def mkdirsNative(path: String): Unit =
        // Create directory and parents, ignore EEXIST
        val parts = path.split('/')
        val sb    = new StringBuilder
        var i     = 0
        while i < parts.length do
            if parts(i).nonEmpty then
                sb.append('/')
                sb.append(parts(i))
                Zone:
                    val _ = PosixFileBindings.mkdir(toCString(sb.toString), 0x1ed) // 0755
                    // Ignore errors (EEXIST is fine)
            end if
            i += 1
        end while
    end mkdirsNative

end NativeFileSource

/** POSIX FFI bindings for file I/O.
  *
  * Uses `@extern` bindings to the platform libc. Only the fields we need are included. The struct layouts use CStruct types that must match
  * the platform ABI. We use a simplified struct with only the fields we read (size and mtime).
  *
  * Note: fstat and stat for size/mtime queries now delegate to scalanative.posix.sys.stat which provides a canonical cross-platform struct.
  * The custom StatBuf here is only used in listDir methods to check file-type bits in st_mode (S_IFREG / S_IFDIR).
  */
@extern
private object PosixFileBindings:

    /** Simplified stat struct used only for directory listing file-type checks.
      *
      * Layout: we only need the mode field to check S_IFREG / S_IFDIR. The CStruct8 of Longs is used solely to satisfy the ABI requirement
      * that the buffer is large enough. Field `_2` maps to st_mode bits on this custom layout; exact bit positions are tested against
      * S_IFREG (0x8000) and S_IFDIR (0x4000) masks in listDirNative.
      */
    type StatBuf = CStruct8[Long, Long, Long, Long, Long, Long, Long, Long]

    /** POSIX open(2). flags: 0 = O_RDONLY. */
    def open(path: CString, flags: CInt): CInt = extern

    /** POSIX open(2) with mode (for O_CREAT). */
    @name("open")
    def openCreate(path: CString, flags: CInt, mode: CInt): CInt = extern

    /** POSIX read(2). */
    def read(fd: CInt, buf: Ptr[Byte], count: CInt): CInt = extern

    /** POSIX write(2). */
    def write(fd: CInt, buf: Ptr[Byte], count: CInt): CInt = extern

    /** POSIX close(2). */
    def close(fd: CInt): CInt = extern

    /** POSIX stat(2) for directory listing only. Follows symlinks.
      *
      * Returns 0 on success. The `buf` is a raw 64-byte opaque buffer; callers access only `_2` for mode bits.
      */
    def stat(path: CString, buf: Ptr[StatBuf]): CInt = extern

    /** POSIX rename(2). Atomic on POSIX when on the same filesystem. */
    def rename(from: CString, to: CString): CInt = extern

    /** POSIX mkdir(2). Mode 0755 for directories. */
    def mkdir(path: CString, mode: CInt): CInt = extern

    /** POSIX opendir(3). */
    def opendir(path: CString): Ptr[Byte] = extern

    /** POSIX readdir(3). Returns null at end of directory. */
    def readdir(dir: Ptr[Byte]): Ptr[Byte] = extern

    /** POSIX closedir(3). */
    def closedir(dir: Ptr[Byte]): CInt = extern

    /** Extract the d_name field from a dirent struct.
      *
      * The dirent struct layout varies by platform. We use a helper that returns a CString pointer to the name. This avoids hardcoding
      * struct offsets.
      */
    @name("kyo_reflect_dirent_name")
    def direntName(entry: Ptr[Byte]): CString = extern

    /** O_WRONLY flag constant. Provided as a C extern to be platform-independent. */
    @name("kyo_reflect_O_WRONLY")
    def O_WRONLY(): CInt = extern

    /** O_CREAT flag constant. */
    @name("kyo_reflect_O_CREAT")
    def O_CREAT(): CInt = extern

    /** O_TRUNC flag constant. */
    @name("kyo_reflect_O_TRUNC")
    def O_TRUNC(): CInt = extern

end PosixFileBindings
