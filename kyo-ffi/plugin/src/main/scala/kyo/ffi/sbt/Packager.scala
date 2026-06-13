package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import sbt.IO

/** Copies compiled native artifacts into the runtime-expected resource layout.
  *
  * JVM layout:
  * {{{
  *   META-INF/native/{os}-{arch}/lib{libraryId}.{ext}
  * }}}
  *
  * JS layout (koffi resolves via `require.resolve`-style relative lookup):
  * {{{
  *   kyo-ffi/native/{os}-{arch}/lib{libraryId}.{ext}
  * }}}
  *
  * Native: no-op, Scala Native links C sources directly.
  *
  * The compile output carries a disambiguating suffix (`libkyo_tcp-linux-x86_64.so`);
  * this stripper restores the canonical name `NativeLoader`/koffi look up at runtime
  * (`libkyo_tcp.so`).
  */
private[sbt] object Packager {

    /** Platform-aware artifact copy. Dispatches to `copyForCurrentPlatform` (JVM) or
      * `copyForJs` (JS); Native returns `Nil` because C sources are linked by Scala
      * Native itself, not packaged as resources.
      */
    def copyForPlatform(platform: String, artifacts: Seq[File], resDir: File, libraryId: String): Seq[File] =
        platform match {
            case "Native" => Nil
            case "JS"     => copyForJs(artifacts, resDir, libraryId)
            case _        => copyForCurrentPlatform(artifacts, resDir, libraryId)
        }

    /** Multi-library variant: take a sequence of `(libraryId, artifacts)` tuples and
      * dispatch each through `copyForPlatform`. Returns the concatenated list of
      * destination files.
      */
    def copyForPlatformMulti(
        platform: String,
        libs: Seq[(String, Seq[File])],
        resDir: File
    ): Seq[File] =
        libs.flatMap { case (id, artifacts) => copyForPlatform(platform, artifacts, resDir, id) }

    def copyForCurrentPlatform(artifacts: Seq[File], resDir: File, libraryId: String): Seq[File] = {
        val os      = CCompiler.detectOs()
        val arch    = CCompiler.detectArch()
        val destDir = new File(resDir, s"$os-$arch")
        IO.createDirectory(destDir)
        artifacts.map { a =>
            val dest = new File(destDir, canonicalName(a.getName, os, arch))
            Files.copy(a.toPath, dest.toPath, StandardCopyOption.REPLACE_EXISTING)
            dest
        }
    }

    /** JS variant: copy to a JS-friendly resource path. The generated JS shim resolves
      * the native lib via `__dirname`-relative path (or `require.resolve`), so the
      * artifact lives under a bundle-friendly subtree instead of `META-INF/native/`.
      *
      * `resDir` here is the project's managed-resource dir; we produce `kyo-ffi/native/...`
      * underneath that so Scala.js's linker picks it up and ships it alongside the JS output.
      */
    def copyForJs(artifacts: Seq[File], resDir: File, libraryId: String): Seq[File] = {
        val os   = CCompiler.detectOs()
        val arch = CCompiler.detectArch()
        // Replace the leading META-INF/native segment with kyo-ffi/native so Node-side
        // resolution is straightforward. `resDir` is `<resourceManaged>/META-INF/native`
        // when invoked from the plugin; swap the top two segments.
        val parent = resDir.getParentFile.getParentFile
        val bundle = new File(parent, s"kyo-ffi/native/$os-$arch")
        IO.createDirectory(bundle)
        artifacts.map { a =>
            val dest = new File(bundle, canonicalName(a.getName, os, arch))
            Files.copy(a.toPath, dest.toPath, StandardCopyOption.REPLACE_EXISTING)
            dest
        }
    }

    // Strip platform suffix for the canonical runtime-expected name:
    //   libkyo_tcp-linux-x86_64.so -> libkyo_tcp.so
    private def canonicalName(name: String, os: String, arch: String): String = {
        val dot = name.lastIndexOf('.')
        if (dot < 0) name
        else {
            val ext        = name.substring(dot)
            val dropSuffix = s"-$os-$arch$ext"
            if (name.endsWith(dropSuffix)) name.substring(0, name.length - dropSuffix.length) + ext
            else name
        }
    }
}
