package kyo.ffi.sbt

/** The `<os>-<arch>` tags kyo's natives are named and packaged by, for a build outside this plugin.
  *
  * The tag is load-bearing rather than cosmetic: it is the directory a library sits in under `META-INF/native/`, the
  * classifier of the artifact carrying it, and the thing a consumer's build has to agree with exactly, since a tag
  * spelled differently resolves nothing. So the answers come from the same detection the packaging used, rather than
  * from a second reading of `os.name` that could drift from it.
  */
object NativeTargets {

    /** Every tag this plugin's packaging supports. */
    def supported: Seq[String] = CCompiler.supportedOsArchTags

    /** The tag of the machine running the build. */
    def host: String = s"${CCompiler.detectOs()}-${CCompiler.detectArch()}"

    /** The `os` half of a tag: `darwin`, `linux`, `linux-musl` or `windows`. */
    def osOf(tag: String): String = CCompiler.parseOsArch(tag)._1

    /** The file name a packaged shared library carries: `lib<id>.<ext>`, or `<id>.dll` on Windows.
      *
      * The compile output carries a disambiguating `-<os>-<arch>` suffix that `Packager` strips when staging into
      * `META-INF/native/<os-arch>/`, so this is the staged name, which is what a consumer looks for and what `-l<id>`
      * resolves. Composed from the same two pieces the packaging uses rather than spelled again here.
      */
    def libraryFileName(libraryId: String, os: String): String =
        s"${CCompiler.libPrefix(os)}$libraryId.${CCompiler.libExtension(os)}"

    /** The tag a Scala Native target triple names, or None when the triple is not one kyo publishes for.
      *
      * Scala Native writes the triple as `<arch>-<vendor>-<os>[-<abi>]`, and the abi is where musl appears, which is a
      * separate pole here because a glibc library does not load under musl.
      */
    def ofTriple(triple: String): Option[String] = {
        val parts = triple.split('-').toSeq
        val arch = parts.headOption.map {
            case "aarch64" | "arm64"     => "aarch64"
            case "x86_64" | "amd64"      => "x86_64"
            case other                   => other
        }
        // The os part carries a version on darwin (`arm64-apple-darwin23.3.0`), so these match on a prefix rather than
        // on equality.
        val os =
            if (parts.exists(p => p.startsWith("darwin") || p.startsWith("macos"))) Some("darwin")
            else if (parts.exists(p => p.startsWith("windows") || p == "msvc" || p.startsWith("mingw"))) Some("windows")
            else if (parts.contains("linux")) Some(if (parts.exists(_.startsWith("musl"))) "linux-musl" else "linux")
            else None
        for {
            a <- arch
            o <- os
            tag = s"$o-$a"
            if supported.contains(tag)
        } yield tag
    }
}
