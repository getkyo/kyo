package kyo.ffi.sbt

import java.io.File
import java.io.FileInputStream

/** Reads a native artifact's own binary format and architecture from its header, so a packaging check can
  * tell what a file IS rather than what its path claims.
  *
  * A shared library filed under the wrong platform key is invisible to every layout-based check: the
  * directory name, the file name and the extension can all be self-consistent while the bytes inside are for
  * another OS or another CPU. The one thing that cannot lie is the object header, so that is what this reads.
  *
  * One axis it genuinely cannot separate: glibc from musl. A `linux-x86_64` and a `linux-musl-x86_64`
  * shared library have identical ELF headers, so a native staged under the wrong libc flavour passes here.
  * That case rests on producer discipline instead, where the vendored-library build scripts derive the
  * os-arch from the host they run on and refuse a cross-OS build outright.
  */
private[sbt] object NativeArtifactFormat {

    sealed trait Format { def label: String }
    case object Elf     extends Format { val label = "ELF"     }
    case object MachO   extends Format { val label = "Mach-O"  }
    case object Pe      extends Format { val label = "PE"      }
    case object Unknown extends Format { val label = "unknown" }

    /** How many leading bytes the format and architecture probes read. The furthest field is the PE machine
      * word, which sits four bytes past the PE signature offset held at 0x3c; a 4 KiB window covers every
      * realistic e_lfanew.
      */
    private val HeaderBytes = 4096

    private def header(file: File): Array[Byte] = {
        val buf = new Array[Byte](HeaderBytes)
        val read =
            try {
                val in = new FileInputStream(file)
                try {
                    var total = 0
                    var n     = 0
                    while (total < HeaderBytes && { n = in.read(buf, total, HeaderBytes - total); n } > 0) total += n
                    total
                } finally in.close()
            } catch { case _: Exception => -1 }
        if (read <= 0) new Array[Byte](0) else buf.take(read)
    }

    /** The object format of `file`, from its first bytes. `Unknown` for anything unreadable or unrecognized. */
    def of(file: File): Format = formatOf(header(file))

    private def formatOf(head: Array[Byte]): Format =
        if (head.length < 4) Unknown
        else {
            def u(i: Int): Int = head(i) & 0xff
            val be             = (u(0) << 24) | (u(1) << 16) | (u(2) << 8) | u(3)
            if (u(0) == 0x7f && u(1) == 'E' && u(2) == 'L' && u(3) == 'F') Elf
            // Mach-O thin (32/64, both endiannesses) and the fat/universal archive magics.
            else if (be == 0xfeedface || be == 0xfeedfacf || be == 0xcefaedfe || be == 0xcffaedfe) MachO
            else if (be == 0xcafebabe || be == 0xbebafeca || be == 0xcafebabf || be == 0xbfbafeca) MachO
            else if (u(0) == 'M' && u(1) == 'Z') Pe
            else Unknown
        }

    /** What a header says about the CPU it was built for.
      *
      * The three cases are distinct on purpose. "I could not read it" and "I read it and it says
      * something else" are different answers, and collapsing them into `None` makes an artifact for a
      * CPU nobody builds for pass a check whose whole job is to reject it.
      */
    sealed trait ArchReading

    /** No architecture could be read: an unreadable file, an unrecognized format, or a truncated or
      * malformed header. The check treats this as nothing to assert.
      */
    case object ArchUnreadable extends ArchReading

    /** A supported `CCompiler.supportedArch` tag. */
    final case class ArchKnown(arch: String) extends ArchReading

    /** A machine field that parsed cleanly and names a CPU outside the supported set (a 32-bit ARM
      * object, say). Not something to package under any platform key this build knows.
      */
    final case class ArchForeign(machine: String) extends ArchReading

    /** The CPU architecture `file` was built for, as a `CCompiler.supportedArch` tag, or `None` when the
      * header does not name a supported one. A fat Mach-O answers with the first slice that matches.
      */
    def archOf(file: File): Option[String] = archReadingOf(file) match {
        case ArchKnown(a) => Some(a)
        case _            => None
    }

    /** The full reading, which distinguishes an unreadable header from one naming a foreign CPU. */
    def archReadingOf(file: File): ArchReading = archReadingOfHeader(header(file))

    private def archReadingOfHeader(head: Array[Byte]): ArchReading = {
        def u(i: Int): Int = if (i < head.length) head(i) & 0xff else -1
        def le16(i: Int)   = if (u(i) < 0 || u(i + 1) < 0) -1 else u(i) | (u(i + 1) << 8)
        def le32(i: Int)   = if (le16(i) < 0 || le16(i + 2) < 0) -1L else (le16(i).toLong | (le16(i + 2).toLong << 16))
        def be32(i: Int) =
            if (u(i) < 0 || u(i + 3) < 0) -1L
            else (u(i).toLong << 24) | (u(i + 1).toLong << 16) | (u(i + 2).toLong << 8) | u(i + 3).toLong

        // Each format has a sentinel meaning "no machine named" (ELF EM_NONE, Mach-O cputype 0, PE
        // IMAGE_FILE_MACHINE_UNKNOWN). That is the header declining to say, which is unreadable rather
        // than foreign; only a value that names a real, different CPU is foreign.
        def elfArch(machine: Int): ArchReading = machine match {
            case -1 | 0 => ArchUnreadable
            case 0x3e   => ArchKnown("x86_64")  // EM_X86_64
            case 0xb7   => ArchKnown("aarch64") // EM_AARCH64
            case m      => ArchForeign(f"ELF machine 0x$m%x")
        }
        def machoArch(cpuType: Long): ArchReading = cpuType match {
            case -1L | 0L    => ArchUnreadable
            case 0x01000007L => ArchKnown("x86_64")  // CPU_TYPE_X86_64
            case 0x0100000cL => ArchKnown("aarch64") // CPU_TYPE_ARM64
            case c           => ArchForeign(f"Mach-O cputype 0x$c%x")
        }

        formatOf(head) match {
            case Elf =>
                // e_machine is a 2-byte field at offset 18; EI_DATA at offset 5 gives the endianness.
                val littleEndian = u(5) == 1
                elfArch(if (littleEndian) le16(18) else if (u(19) < 0) -1 else (u(18) << 8) | u(19))
            case MachO =>
                val be = be32(0)
                // Fat/universal: a big-endian slice count at offset 4, then one record per slice, each
                // starting with its cputype. fat_arch is 20 bytes; fat_arch_64 (FAT_MAGIC_64) is 32,
                // because its offset and size fields are 64-bit. Walking a fat64 header at stride 20
                // reads garbage for every slice after the first.
                val fatStride =
                    if (be == 0xcafebabeL) Some(20)
                    else if (be == 0xcafebabfL) Some(32)
                    else None
                fatStride match {
                    case Some(stride) =>
                        val count = be32(4)
                        val slices =
                            if (count < 0 || count > 64) Nil
                            else (0 until count.toInt).map(i => machoArch(be32(8 + i * stride))).toList
                        slices.collectFirst { case k: ArchKnown => k }
                            .orElse(slices.collectFirst { case f: ArchForeign => f })
                            .getOrElse(ArchUnreadable)
                    // The byte-swapped fat magics are a big-endian host's view of the same header. No
                    // toolchain in this build produces one, and reading it as a thin header would take
                    // the slice count for a cputype, so it is reported as unreadable rather than guessed.
                    case None if be == 0xbebafecaL || be == 0xbfbafecaL => ArchUnreadable
                    case None                                           =>
                        // Thin: cputype is the 4 bytes after the magic, in the file's own endianness.
                        val littleEndian = be == 0xcefaedfeL || be == 0xcffaedfeL
                        machoArch(if (littleEndian) le32(4) else be32(4))
                }
            case Pe =>
                // e_lfanew at 0x3c points at the "PE\0\0" signature; the machine word follows it.
                val peOffset = le32(0x3c)
                if (peOffset < 0 || peOffset + 6 > head.length) ArchUnreadable
                else {
                    val p = peOffset.toInt
                    if (u(p) != 'P' || u(p + 1) != 'E' || u(p + 2) != 0 || u(p + 3) != 0) ArchUnreadable
                    else
                        le16(p + 4) match {
                            case -1 | 0 => ArchUnreadable       // IMAGE_FILE_MACHINE_UNKNOWN
                            case 0x8664 => ArchKnown("x86_64")  // IMAGE_FILE_MACHINE_AMD64
                            case 0xaa64 => ArchKnown("aarch64") // IMAGE_FILE_MACHINE_ARM64
                            case m      => ArchForeign(f"PE machine 0x$m%x")
                        }
                }
            case Unknown => ArchUnreadable
        }
    }

    /** The object format a `<os>-<arch>` platform key requires. `None` for an os token this does not know,
      * which a check treats as nothing to assert rather than as a failure.
      */
    def expectedFormat(platformKey: String): Option[Format] =
        if (platformKey.startsWith("darwin")) Some(MachO)
        else if (platformKey.startsWith("linux")) Some(Elf)
        else if (platformKey.startsWith("windows")) Some(Pe)
        else None

    /** The shared-library extension a `<os>-<arch>` platform key requires, without the dot. */
    def expectedExtension(platformKey: String): Option[String] =
        if (platformKey.startsWith("darwin")) Some("dylib")
        else if (platformKey.startsWith("linux")) Some("so")
        else if (platformKey.startsWith("windows")) Some("dll")
        else None

    /** The library id a canonical artifact name carries (`libmachine_macos.dylib` -> `machine_macos`). */
    def libraryIdOf(fileName: String): Option[String] = {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) None
        else {
            val base = fileName.substring(0, dot)
            if (base.startsWith("lib") && base.length > 3) Some(base.substring(3))
            else Some(base)
        }
    }

    /** The architecture tag a `<os>-<arch>` platform key names, when it is one this can verify. */
    def expectedArch(platformKey: String): Option[String] =
        CCompiler.supportedArch.find(a => platformKey == a || platformKey.endsWith("-" + a))

    /** Why `file`, sitting under `platformKey`, does not belong there; `None` when it does.
      *
      * Three halves matter. The format check catches a foreign binary under a key it cannot load on. The
      * extension check catches the same file renamed to fit, which is what a copy step keyed on the build
      * host rather than on the artifact produces. The architecture check catches a native that is right
      * about its OS and wrong about its CPU, which is what an ignored cross-compilation flag produces and
      * what no other layer sees: the vendored-library build scripts assert the arch of the third-party
      * ARCHIVE they stage, not of the shim a producer later links from it, and a prebuilt arriving through
      * the shared pool passes no script at all.
      *
      * An UNREADABLE architecture is not a failure: a stripped or unusual object still has a valid
      * format, and refusing to package it because the machine field could not be parsed would turn a
      * check into an obstacle. A machine field that parses cleanly and names a CPU outside the supported
      * set IS a failure, because it is an artifact for a platform nothing here builds for. Collapsing
      * those two into "no architecture" is what would let such a file through.
      */
    def mismatch(platformKey: String, file: File): Option[String] = {
        val name = file.getName
        val extMismatch = expectedExtension(platformKey).flatMap { want =>
            val dot = name.lastIndexOf('.')
            val got = if (dot < 0) "" else name.substring(dot + 1)
            if (got == want) None
            else Some(s"$platformKey/$name has extension '$got' where $platformKey requires '$want'")
        }
        val formatMismatch = expectedFormat(platformKey).flatMap { want =>
            val got = of(file)
            if (got == want) None
            else Some(s"$platformKey/$name is a ${got.label} object where $platformKey requires ${want.label}")
        }
        val archMismatch = expectedArch(platformKey).flatMap { want =>
            archReadingOf(file) match {
                case ArchKnown(got) if got != want => Some(s"$platformKey/$name is a $got object where $platformKey requires $want")
                case ArchForeign(machine)          => Some(s"$platformKey/$name has $machine, which is not $want as $platformKey requires")
                case _                             => None
            }
        }
        extMismatch.orElse(formatMismatch).orElse(archMismatch)
    }
}
