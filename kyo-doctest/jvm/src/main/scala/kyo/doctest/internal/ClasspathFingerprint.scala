package kyo.doctest.internal

import java.security.MessageDigest
import kyo.*
import kyo.doctest.*

/** Computes a stable SHA-256 fingerprint over a classpath.
  *
  * The fingerprint covers the content of every jar (or class file, for directory entries) on the classpath, keyed by sorted entry path so
  * the result is invariant to classpath ordering. This is used by BlockCache to invalidate entries when the classpath changes.
  */
private[kyo] object ClasspathFingerprint:

    /** Computes a hex-encoded SHA-256 fingerprint for the supplied classpath.
      *
      * Algorithm: for each classpath entry, produce a (path, contentHash) pair. Pairs are sorted by path string before the final digest is
      * computed so the result is independent of classpath ordering.
      *
      * For jar files, the content hash is SHA-256 of the jar bytes. For directories, the content hash is SHA-256 over all .class files in
      * the directory tree, sorted by relative path.
      *
      * @param classpath
      *   Classpath entries to fingerprint.
      * @return
      *   A hex-encoded SHA-256 string, stable for unchanged classpath contents.
      */
    def compute(classpath: Chunk[kyo.Path])(using Frame): String < (Sync & Async & Abort[Doctest.Error]) =
        Kyo.foreach(classpath.toSeq) { p =>
            hashEntry(p).map(h => (p.toString, h))
        }.map { pairs =>
            val outer  = MessageDigest.getInstance("SHA-256")
            val sorted = pairs.sortBy(_._1)
            // Imperative protocol: MessageDigest accumulates state via update; not a refactor target.
            for (path, hash) <- sorted do
                outer.update(path.getBytes("UTF-8"))
                outer.update(':'.toByte)
                outer.update(hash)
                outer.update('\n'.toByte)
            end for
            hexString(outer.digest())
        }

    // Produce a raw-bytes hash for a single classpath entry.
    private def hashEntry(entry: kyo.Path)(using Frame): Array[Byte] < (Sync & Async & Abort[Doctest.Error]) =
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(entry, "exists", e))) {
            Path.runReadOnly(entry.exists)
        }.flatMap { exists =>
            if !exists then
                // Missing entry: hash the path string itself so presence vs absence is detectable.
                sha256Bytes(entry.toString.getBytes("UTF-8"))
            else
                Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(entry, "isDirectory", e))) {
                    Path.runReadOnly(entry.isDirectory)
                }.flatMap { isDir =>
                    if isDir then hashDirectory(entry)
                    else
                        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(entry, "read", e))) {
                            Path.runReadOnly(entry.readBytes).map(span => sha256Bytes(span.toArray))
                        }
                }
        }
    end hashEntry

    // Hash all .class files in a directory tree, sorted by relative path.
    // Walk requires Scope; we run that scope locally (Scope.run introduces Async in the row).
    private def hashDirectory(dir: kyo.Path)(using Frame): Array[Byte] < (Sync & Async & Abort[Doctest.Error]) =
        Scope.run {
            Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(dir, "walk", e))) {
                Path.runReadOnly(dir.walk.run)
            }
        }.flatMap { allPaths =>
            val classFiles = allPaths.filter(_.toString.endsWith(".class"))
            // Sort by path string for stable hashing.
            val sortedFiles = classFiles.toSeq.sortBy(_.toString)
            Kyo.foreach(sortedFiles) { f =>
                Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(f, "read", e))) {
                    Path.runReadOnly(f.readBytes).map(span => (f.toString, span.toArray))
                }
            }.map { entries =>
                val digest = MessageDigest.getInstance("SHA-256")
                // Imperative protocol: MessageDigest accumulates state via update; not a refactor target.
                for (path, bytes) <- entries do
                    digest.update(path.getBytes("UTF-8"))
                    digest.update(':'.toByte)
                    digest.update(bytes)
                    digest.update('\n'.toByte)
                end for
                digest.digest()
            }
        }
    end hashDirectory

    private def sha256Bytes(data: Array[Byte]): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(data)

    private def hexString(bytes: Array[Byte]): String =
        bytes.map(b => f"${b & 0xff}%02x").mkString

end ClasspathFingerprint
