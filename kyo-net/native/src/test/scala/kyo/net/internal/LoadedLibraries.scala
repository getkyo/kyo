package kyo.net.internal

import kyo.*
import scala.scalanative.posix.dlfcn
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The shared libraries loaded into this process, by path: `/proc/self/maps` on Linux, dyld's image list on macOS.
  *
  * dyld's two functions are looked up with `dlsym` rather than declared `@extern`, because an extern reference to them would leave the
  * Linux link with undefined symbols.
  */
object LoadedLibraries:

    def paths(): Chunk[String] =
        val maps = new java.io.File("/proc/self/maps")
        if maps.isFile then fromProcMaps(maps) else fromDyld()

    /** The loaded libssl and libcrypto images, which only a system OpenSSL provides: BoringSSL is linked into the binary. */
    def systemTls(): Chunk[String] =
        paths().filter { path =>
            val name = path.substring(path.lastIndexOf('/') + 1)
            name.startsWith("libssl.") || name.startsWith("libcrypto.")
        }

    /** Whether this binary carries a working BoringSSL. */
    def boringSslLinked()(using AllowUnsafe): Boolean =
        BoringSslProvider.probe.isAvailable

    private def fromProcMaps(maps: java.io.File): Chunk[String] =
        val source = scala.io.Source.fromFile(maps)
        try
            Chunk.from(source.getLines().flatMap(_.trim.split("\\s+").drop(5).headOption).filter(_.startsWith("/")).toSeq.distinct)
        finally source.close()
    end fromProcMaps

    private def fromDyld(): Chunk[String] =
        val self    = dlfcn.dlopen(null, dlfcn.RTLD_LAZY)
        val countFn = dlfcn.dlsym(self, c"_dyld_image_count")
        val nameFn  = dlfcn.dlsym(self, c"_dyld_get_image_name")
        if countFn == null || nameFn == null then
            throw new IllegalStateException("neither /proc/self/maps nor dyld's image list is available")
        val countOf = CFuncPtr.fromPtr[CFuncPtr0[CUnsignedInt]](countFn)
        val name    = CFuncPtr.fromPtr[CFuncPtr1[CUnsignedInt, CString]](nameFn)
        val count   = countOf().toInt
        Chunk.from((0 until count).map(i => fromCString(name(i.toUInt))))
    end fromDyld

end LoadedLibraries
