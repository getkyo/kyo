package kyo.internal.tasty.snapshot

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import kyo.*
import kyo.internal.tasty.query.JarCentralDirectory
import scala.jdk.CollectionConverters.*

/** JVM platform: walk the JAR central directory to compute a content-addressed digest.
  *
  * Uses JarCentralDirectory.read to enumerate CEN entries and feed their (name, CRC32) pairs to the xxh64-custom digestForJar. This
  * digest is stable across mtime changes and machine boundaries. The CRC32 values stored in the CEN are computed by the jar
  * writer and reflect the actual entry byte content.
  *
  * On missing or corrupt jars, JarCentralDirectory.read propagates IOException rather than returning Chunk.empty;
  * silent Chunk.empty would yield digest 0L causing false-positive cache hits. The IOException surfaces as a Kyo Panic in any
  * Sync.defer context, making the failure loud and observable.
  */
private[kyo] object PlatformDigest:

    /** Compute the content-addressed digest for a jar root on JVM.
      *
      * Reads the CEN via RandomAccessFile under AllowUnsafe; converts JarEntry records to JarDigestEntry and delegates to
      * DigestComputer.digestForJar. Propagates IOException if the jar is missing or corrupt.
      *
      * Unsafe: synchronous JAR CEN walk via JarCentralDirectory.read; AllowUnsafe bounded to this call site; no Scope required.
      */
    def digestForJarRoot(jarPath: String): Long =
        // Unsafe: JVM-only platform bridge. JarCentralDirectory.read takes (using AllowUnsafe) to access
        // the RandomAccessFile + ByteBuffer central-directory walk; this digestForJarRoot method has no
        // effect row (it is called from a lazy classpath-bootstrap path that must return a plain Long),
        // so the AllowUnsafe proof is provided explicitly at the call boundary. IOException propagates
        // on missing/corrupt jar; we do not return 0L for absent jars.
        // Exclude the bundled snapshot entry itself: the digest must cover classpath content only, not the snapshot
        // metadata. This makes the digest stable regardless of whether a snapshot is present in the jar.
        val snapshotEntry = kyo.internal.tasty.query.BundledSnapshotProbe.snapshotEntryPath
        val entries       = JarCentralDirectory.read(jarPath)(using AllowUnsafe.embrace.danger)
        val digestEntries = entries
            .filter(e => e.name != snapshotEntry)
            .map(e => DigestComputer.JarDigestEntry(e.name, e.crc32))
        DigestComputer.digestForJar(digestEntries)
    end digestForJarRoot

    /** Collect (path, mtime, size) triples for all .tasty files under a jrt:/ root.
      *
      * Walks the JRT filesystem (accessible on JVM via FileSystems.getFileSystem(URI.create("jrt:/"))) for files whose names end in
      * ".tasty". Each returned path string is prefixed with "jrt:/". Mtime and size are obtained from the JRT filesystem attributes.
      *
      * Returns Seq.empty if the JRT filesystem is unavailable or if the root path does not exist within it. IOException from the JRT
      * walk is converted to TastyError.SnapshotIoError.
      */
    def collectJrtStats(root: String)(using Frame): Seq[(String, Long, Long)] < (Sync & Abort[TastyError]) =
        Sync.defer {
            try
                val fs = jrtFileSystem
                if fs == null then Seq.empty
                else
                    val jrtPath = fs.getPath(root.stripPrefix("jrt:/"))
                    if !Files.exists(jrtPath) then Seq.empty
                    else
                        val results = scala.collection.mutable.ArrayBuffer.empty[(String, Long, Long)]
                        Files.walk(jrtPath).iterator().asScala.foreach { p =>
                            if p.getFileName != null && p.getFileName.toString.endsWith(".tasty") then
                                val pathStr = "jrt:/" + p.toString
                                val mtime   = Files.getLastModifiedTime(p).toMillis
                                val size    = Files.size(p)
                                results += ((pathStr, mtime, size))
                        }
                        results.toSeq
                    end if
                end if
            catch
                case e: java.io.IOException =>
                    Abort.fail(TastyError.SnapshotIoError(s"jrt walk $root: ${e.getMessage}"))
        }

    /** Lazy JRT filesystem handle. Returns null if JRT filesystem is unavailable. */
    private lazy val jrtFileSystem: java.nio.file.FileSystem =
        try FileSystems.getFileSystem(URI.create("jrt:/"))
        catch
            case _: Throwable => null

end PlatformDigest
