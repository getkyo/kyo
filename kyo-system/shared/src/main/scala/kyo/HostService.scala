package kyo

import java.nio.charset.Charset

private[kyo] object HostService:

    def apply()(using Frame): Path.Service[Sync] = new HostService

    def rootConfined(root: Path)(using Frame): Path.Service[Sync] < (Sync & Abort[FileException]) =
        // Unsafe: resolves the confinement root's real path once at construction
        Sync.Unsafe.defer(Abort.get(root.unsafe.realPath())).map(rootReal => new RootConfinedHostService(rootReal))

    final class HostService(using Frame) extends Path.Service[Sync]:
        val disposition: Path.Disposition = Path.Disposition.AutoCommit

        def exists(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.exists into the safe tier
            Sync.Unsafe.defer(path.unsafe.exists())
        def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.exists(followLinks) into the safe tier
            Sync.Unsafe.defer(path.unsafe.exists(followLinks))
        def isDirectory(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isDirectory into the safe tier
            Sync.Unsafe.defer(path.unsafe.isDirectory())
        def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isRegularFile into the safe tier
            Sync.Unsafe.defer(path.unsafe.isRegularFile())
        def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isSymbolicLink into the safe tier
            Sync.Unsafe.defer(path.unsafe.isSymbolicLink())
        def realPath(path: Path): Path < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.realPath; the Result maps to Abort[FileException]
            Sync.Unsafe.defer(Abort.get(path.unsafe.realPath()))
        def read(path: Path): String < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.read into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read()))
        def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.read(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read(charset)))
        def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readBytes()))
        def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines()))
        def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readLines(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines(charset)))
        def size(path: Path): Long < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.size into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.size()))
        def stat(path: Path): Path.PathStat < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.stat into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.stat()))
        def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openRead into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openRead()))
        def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openReadLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadLines(charset)))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openWalk into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWalk(maxDepth, followLinks)))
        def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.write into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.write(value, createFolders)))
        def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.writeBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeBytes(value, createFolders)))
        def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.writeLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeLines(value, createFolders)))
        def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.append into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.append(value, createFolders)))
        def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.appendBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendBytes(value, createFolders)))
        def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.appendLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendLines(value, createFolders)))
        def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.truncate into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.truncate(size)))
        def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.setLastModified into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.setLastModified(epochMs)))
        def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openWrite into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append, createFolders)))
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileException](handle.writeBytes(chunk)))
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileException](handle.writeString(value, charset)))
        def mkDir(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.mkDir into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkDir()))
        def mkFile(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.mkFile into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkFile()))
        def list(path: Path): Chunk[Path] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.list into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list()))
        def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.list(glob) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list(glob)))
        def move(
            from: Path,
            to: Path,
            replaceExisting: Boolean,
            atomicMove: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.move into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.move(to, replaceExisting, atomicMove, createFolders)))
        def copy(
            from: Path,
            to: Path,
            followLinks: Boolean,
            replaceExisting: Boolean,
            copyAttributes: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.copy into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.copy(to, followLinks, replaceExisting, copyAttributes, createFolders)))
        def remove(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.remove into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.remove()))
        def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.removeExisting into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeExisting()))
        def removeAll(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.removeAll into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeAll()))
        def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) =
            Path.tempDirUnscoped(prefix).map { dir =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the created temp dir at Scope exit
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
    end HostService

    final class RootConfinedHostService(rootReal: Path)(using Frame) extends Path.Service[Sync]:
        private val host                  = new HostService
        val disposition: Path.Disposition = Path.Disposition.AutoCommit

        private def confined(path: Path): Path < (Sync & Abort[FileException]) =
            // Unsafe: probes target existence to choose between realpath and nearest-parent checks
            Sync.Unsafe.defer(path.unsafe.exists()).map {
                case true  => Sync.Unsafe.defer(Abort.get(path.unsafe.realPath())).map(check)
                case false => nearestExistingParent(path).map(check).andThen(path)
            }
        private def check(real: Path): Path < Abort[FileException] =
            if real.parts.take(rootReal.parts.size) == rootReal.parts then real
            else Abort.fail(FileAccessDeniedException(real))
        private def nearestExistingParent(path: Path): Path < (Sync & Abort[FileException]) =
            path.parent match
                case Absent     => Abort.fail(FileNotFoundException(path))
                case Present(p) =>
                    // Unsafe: probes parent existence to walk to the nearest real ancestor
                    Sync.Unsafe.defer(p.unsafe.exists()).map {
                        case true  => Sync.Unsafe.defer(Abort.get(p.unsafe.realPath()))
                        case false => nearestExistingParent(p)
                    }

        def exists(path: Path): Boolean < (Sync & Abort[FileException]) = confined(path).andThen(host.exists(path))
        def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) =
            confined(path).andThen(host.exists(path, followLinks))
        def isDirectory(path: Path): Boolean < (Sync & Abort[FileException])           = confined(path).andThen(host.isDirectory(path))
        def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException])         = confined(path).andThen(host.isRegularFile(path))
        def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException])        = confined(path).andThen(host.isSymbolicLink(path))
        def realPath(path: Path): Path < (Sync & Abort[FileException])                 = confined(path).andThen(host.realPath(path))
        def read(path: Path): String < (Sync & Abort[FileException])                   = confined(path).andThen(host.read(path))
        def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) = confined(path).andThen(host.read(path, charset))
        def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException])          = confined(path).andThen(host.readBytes(path))
        def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException])       = confined(path).andThen(host.readLines(path))
        def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
            confined(path).andThen(host.readLines(path, charset))
        def size(path: Path): Long < (Sync & Abort[FileException])                = confined(path).andThen(host.size(path))
        def stat(path: Path): Path.PathStat < (Sync & Abort[FileException])       = confined(path).andThen(host.stat(path))
        def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) = confined(path).andThen(host.openRead(path))
        def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openReadLines(path, charset))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openWalk(path, maxDepth, followLinks))
        def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.write(path, value, createFolders))
        def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.writeBytes(path, value, createFolders))
        def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.writeLines(path, value, createFolders))
        def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.append(path, value, createFolders))
        def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.appendBytes(path, value, createFolders))
        def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.appendLines(path, value, createFolders))
        def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) = confined(path).andThen(host.truncate(path, size))
        def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.setLastModified(path, epochMs))
        def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openWrite(path, append, createFolders))
        // writeChunk/writeString carry only a handle (no path), so confinement is not applicable; forward directly.
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) = host.writeChunk(handle, chunk)
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
            host.writeString(handle, value, charset)
        def mkDir(path: Path): Unit < (Sync & Abort[FileException])                     = confined(path).andThen(host.mkDir(path))
        def mkFile(path: Path): Unit < (Sync & Abort[FileException])                    = confined(path).andThen(host.mkFile(path))
        def list(path: Path): Chunk[Path] < (Sync & Abort[FileException])               = confined(path).andThen(host.list(path))
        def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) = confined(path).andThen(host.list(path, glob))
        def move(
            from: Path,
            to: Path,
            replaceExisting: Boolean,
            atomicMove: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            confined(from).andThen(confined(to)).andThen(host.move(from, to, replaceExisting, atomicMove, createFolders))
        def copy(
            from: Path,
            to: Path,
            followLinks: Boolean,
            replaceExisting: Boolean,
            copyAttributes: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            confined(from).andThen(confined(to)).andThen(host.copy(from, to, followLinks, replaceExisting, copyAttributes, createFolders))
        def remove(path: Path): Boolean < (Sync & Abort[FileException])      = confined(path).andThen(host.remove(path))
        def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) = confined(path).andThen(host.removeExisting(path))
        def removeAll(path: Path): Unit < (Sync & Abort[FileException])      = confined(path).andThen(host.removeAll(path))
        // tempDir creates inside rootReal, not in the OS temp dir. Creating within root keeps
        // staged paths confined: the overlay's commit protocol calls lower.move(stagingDir/eN.dat,
        // target) through this service; if stagingDir were in OS temp the confinement check on
        // the source path would fail. Creating in root also lets recoverFromDisk(root) scan for
        // kyo-commit-* dirs without cross-filesystem access. Uniqueness: nanoTime XOR identityHash
        // provides negligible collision probability for the expected number of concurrent commits.
        def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) =
            val uniqueSuffix =
                java.lang.Long.toHexString(java.lang.System.nanoTime() ^ java.lang.System.identityHashCode(this).toLong)
            val dir = rootReal / s"$prefix-$uniqueSuffix"
            host.mkDir(dir).map { _ =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the temp dir created within rootReal
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
        end tempDir
    end RootConfinedHostService
end HostService
