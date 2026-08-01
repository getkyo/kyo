package kyo.ffi.internal

import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** JVM backing for the manifest-driven direct-load pre-check.
  *
  * Enumerates every `META-INF/kyo-ffi/native-manifest/<module>.manifest` resource on the classpath (a per-module
  * path, so kyo-net, kyo-aeron and every FFI module contribute their own file instead of colliding on one fixed
  * name), reads the kyo-ffi runtime version, and delegates the actual bundled-native presence assertion to
  * [[NativeLoader]].
  */
object NativeManifestPlatform:

    private val ManifestDir    = "META-INF/kyo-ffi/native-manifest"
    private val ManifestSuffix = ".manifest"

    /** The raw text of every native manifest on the classpath, merged by [[NativeManifest]]. */
    def manifestTexts: Chunk[String] =
        val loader =
            Option(Thread.currentThread().nn.getContextClassLoader)
                .orElse(Option(getClass.getClassLoader))
                .getOrElse(ClassLoader.getSystemClassLoader.nn)
        val urls = loader.getResources(ManifestDir).nn
        val out  = scala.collection.mutable.ArrayBuffer.empty[String]
        while urls.hasMoreElements do
            readManifestsAt(urls.nextElement().nn, out)
        Chunk.from(out)
    end manifestTexts

    /** Best-effort kyo-ffi runtime version, read from this jar's `Implementation-Version` manifest attribute.
      * [[Absent]] when it cannot be determined (for example running from an exploded classes directory), in which
      * case the version / minRuntime comparison is skipped rather than guessed.
      */
    def runtimeVersion: Maybe[String] =
        try
            getClass.getPackage match
                case null => Absent
                case pkg =>
                    pkg.getImplementationVersion match
                        case null => Absent
                        case v    => Present(v)
        catch case _: Throwable => Absent
    end runtimeVersion

    /** Assert that a library id the manifest declares bundled for the current platform is actually present.
      * Delegates to the JVM [[NativeLoader]]; missing raises [[kyo.ffi.FfiLoadError.LibraryNotFound]].
      */
    def assertBundledPresent(libraryId: String, bundledPlatforms: Set[String]): Unit =
        NativeLoader.assertBundledPresent(libraryId, bundledPlatforms)

    private def readManifestsAt(url: URL, out: scala.collection.mutable.ArrayBuffer[String]): Unit =
        url.getProtocol.nn match
            case "file" =>
                val dir   = new java.io.File(url.toURI.nn)
                val files = if dir.isDirectory then dir.listFiles() else null
                if files != null then
                    files.nn.foreach { f =>
                        if f.isFile && f.getName.nn.endsWith(ManifestSuffix) then
                            out += readFully(new java.io.FileInputStream(f))
                    }
                end if
            case "jar" =>
                // A jar: URL for a directory. `JarURLConnection` exposes the backing jar and the entry name
                // without parsing `jar:file:/path.jar!/inner` by hand or using the deprecated `URL(String)` ctor.
                url.openConnection().nn match
                    case jarConn: java.net.JarURLConnection =>
                        jarConn.setUseCaches(false)
                        val jar = jarConn.getJarFile.nn
                        val prefix = jarConn.getEntryName match
                            case null => ""
                            case n    => n
                        try
                            val entries = jar.entries().nn
                            while entries.hasMoreElements do
                                val e    = entries.nextElement().nn
                                val name = e.getName.nn
                                if !e.isDirectory && name.startsWith(prefix) && name.endsWith(ManifestSuffix) then
                                    out += readFully(jar.getInputStream(e).nn)
                            end while
                        finally jar.close()
                        end try
                    case _ => ()
            case _ =>
                // Any other protocol: try to open the stream directly (best effort).
                try out += readFully(url.openStream().nn)
                catch case _: Throwable => ()
    end readManifestsAt

    private def readFully(in: InputStream): String =
        try new String(in.readAllBytes().nn, StandardCharsets.UTF_8)
        finally in.close()
end NativeManifestPlatform
