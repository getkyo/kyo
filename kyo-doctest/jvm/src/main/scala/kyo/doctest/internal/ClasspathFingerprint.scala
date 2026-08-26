package kyo.doctest.internal

import java.security.MessageDigest
import kyo.*
import kyo.doctest.*

/** Computes a stable SHA-256 fingerprint over a classpath.
  *
  * The fingerprint covers the content of every jar, and of the `.tasty` files for directory entries, keyed by sorted entry path so the
  * result is invariant to classpath ordering. This is used by BlockCache to invalidate entries when the classpath changes.
  *
  * Directory entries are fingerprinted from TASTy rather than bytecode because bytecode is not reproducible: two clean builds of identical
  * sources emit `.class` files that differ in emission order and in whether a dead synthetic accessor is generated. Since the fingerprint
  * is one digest over the whole classpath, a single such file would change it on every build and no cache entry would ever be reused.
  *
  * TASTy is the right granularity rather than merely the stable one. The cache stores only COMPILE results, because runtime-bearing units
  * bypass it entirely (see Orchestrator), and compiling against a classpath reads TASTy. Bytecode layout therefore cannot affect what is
  * cached.
  */
private[kyo] object ClasspathFingerprint:

    /** Computes a hex-encoded SHA-256 fingerprint for the supplied classpath.
      *
      * Algorithm: for each classpath entry, produce a (path, contentHash) pair. Pairs are sorted by path string before the final digest is
      * computed so the result is independent of classpath ordering.
      *
      * For jar files, the content hash is SHA-256 of the jar bytes. For directories, the content hash is SHA-256 over all `.tasty` files in
      * the directory tree, sorted by relative path, falling back to `.class` files for directories that carry no TASTy.
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
        Abort.recover[FileReadException](e => Abort.fail(Doctest.Error.IoError(entry, "exists", e))) {
            entry.exists
        }.flatMap { exists =>
            if !exists then
                // Missing entry: hash the path string itself so presence vs absence is detectable.
                sha256Bytes(entry.toString.getBytes("UTF-8"))
            else
                entry.isDirectory.flatMap { isDir =>
                    if isDir then hashDirectory(entry)
                    else
                        Abort.recover[FileReadException](e => Abort.fail(Doctest.Error.IoError(entry, "read", e))) {
                            entry.readBytes.map(span => sha256Bytes(span.toArray))
                        }
                }
        }
    end hashEntry

    // Hash a directory tree's TASTy, sorted by relative path, falling back to bytecode.
    // Walk requires Scope; we run that scope locally (Scope.run introduces Async in the row).
    private def hashDirectory(dir: kyo.Path)(using Frame): Array[Byte] < (Sync & Async & Abort[Doctest.Error]) =
        Scope.run {
            Abort.recover[FileStructureException](e => Abort.fail(Doctest.Error.IoError(dir, "walk", e))) {
                dir.walk.run
            }
        }.flatMap { allPaths =>
            val tastyFiles = allPaths.filter(_.toString.endsWith(".tasty"))
            // Prefer TASTy: it is what a downstream compile reads, and unlike bytecode it is
            // reproducible across clean builds. Fall back to .class for directories that carry no
            // TASTy at all (Java-only output, resource trees, Scala 2.13 modules), where bytecode
            // is the only signal available; hashing nothing there would make every such directory
            // indistinguishable from any other.
            val selected = if tastyFiles.nonEmpty then tastyFiles else allPaths.filter(_.toString.endsWith(".class"))
            // Sort by path string for stable hashing.
            val sortedFiles = selected.toSeq.sortBy(_.toString)
            Kyo.foreach(sortedFiles) { f =>
                Abort.recover[FileReadException](e => Abort.fail(Doctest.Error.IoError(f, "read", e))) {
                    f.readBytes.map(span => (f.toString, span.toArray))
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
