package kyo.doctest.internal

import java.security.MessageDigest
import kyo.*
import kyo.doctest.*

/** Computes a stable SHA-256 fingerprint over a classpath.
  *
  * The fingerprint covers every jar's content and, for directory entries, the `.tasty` files plus any `.class` file with no TASTy beside
  * it. Entries are keyed by sorted path, so the result is invariant to classpath ordering. BlockCache uses it to invalidate cached results
  * when the classpath changes.
  *
  * Scala output is read from TASTy, not bytecode, because Scala-emitted `.class` files are not reproducible: two clean builds of identical
  * sources differ in emission order and in whether a dead synthetic accessor appears. The fingerprint is one digest over the whole
  * classpath, so a single such file would change the key on every build and no entry would ever be reused. TASTy is also the right
  * granularity, since compiling against a classpath reads TASTy and the cache stores compile results alone (runtime-bearing units bypass
  * it, see Orchestrator).
  *
  * Java output has no TASTy, so it is covered by its `.class` files. Both can share one directory, and hashing only TASTy there would leave
  * javac's output outside the key entirely, letting a Java-only edit go unnoticed and a stale result be reused. javac is deterministic, so
  * including that bytecode costs nothing in reproducibility.
  */
private[kyo] object ClasspathFingerprint:

    /** Computes a hex-encoded SHA-256 fingerprint for the supplied classpath.
      *
      * Algorithm: for each classpath entry, produce a (path, contentHash) pair. Pairs are sorted by path string before the final digest is
      * computed so the result is independent of classpath ordering.
      *
      * For jar files, the content hash is SHA-256 of the jar bytes. For directories, it is SHA-256 over the tree's `.tasty` files together
      * with any `.class` file that has no TASTy beside it, sorted by relative path.
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

    // Hash a directory tree's TASTy plus any TASTy-less bytecode, sorted by relative path.
    // Walk requires Scope; we run that scope locally (Scope.run introduces Async in the row).
    private def hashDirectory(dir: kyo.Path)(using Frame): Array[Byte] < (Sync & Async & Abort[Doctest.Error]) =
        Scope.run {
            Abort.recover[FileStructureException](e => Abort.fail(Doctest.Error.IoError(dir, "walk", e))) {
                dir.walk.run
            }
        }.flatMap { allPaths =>
            // TASTy for everything the Scala compiler produced, because that is what a downstream
            // compile reads and, unlike Scala-emitted bytecode, it is reproducible across clean
            // builds.
            //
            // Plus the `.class` files that have no TASTy alongside them, which is how Java sources
            // in the same directory stay covered. A directory can hold both (kyo-tasty's fixtures
            // module compiles Java and Scala into one output), and selecting TASTy alone there
            // would leave javac's output entirely outside the fingerprint: a Java-only edit would
            // not move the key, and a cached result compiled against the older bytecode would be
            // reused. javac output is deterministic, so including it costs no reproducibility.
            //
            // A class is matched to its TASTy by the outermost name, before any `$`, so a Scala
            // class contributes only its TASTy while its companion, anonymous and nested classes
            // are all excluded with it.
            val tastyFiles = allPaths.filter(_.toString.endsWith(".tasty"))
            val tastyStems = tastyFiles.map(p => p.toString.stripSuffix(".tasty")).toSet
            val classFiles = allPaths.filter(_.toString.endsWith(".class"))
            val javaClasses = classFiles.filterNot { p =>
                val stem = p.toString.stripSuffix(".class")
                val outermost = stem.indexOf('$') match
                    case -1 => stem
                    case i  => stem.substring(0, i)
                tastyStems.contains(outermost)
            }
            val selected = tastyFiles ++ javaClasses
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
