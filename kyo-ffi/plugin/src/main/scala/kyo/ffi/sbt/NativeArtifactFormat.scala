package kyo.ffi.sbt

import java.io.File
import java.io.FileInputStream

/** Reads a native artifact's own binary format from its leading bytes, so a packaging check can tell what a
  * file IS rather than what its path claims.
  *
  * A shared library filed under the wrong platform key is invisible to every layout-based check: the
  * directory name, the file name and the extension can all be self-consistent while the bytes inside are for
  * another OS. The one thing that cannot lie is the object-file magic, so that is what this reads.
  */
private[sbt] object NativeArtifactFormat {

    sealed trait Format { def label: String }
    case object Elf     extends Format { val label = "ELF"     }
    case object MachO   extends Format { val label = "Mach-O"  }
    case object Pe      extends Format { val label = "PE"      }
    case object Unknown extends Format { val label = "unknown" }

    /** The object format of `file`, from its first bytes. `Unknown` for anything unreadable or unrecognized. */
    def of(file: File): Format = {
        val head = new Array[Byte](4)
        val read =
            try {
                val in = new FileInputStream(file)
                try in.read(head)
                finally in.close()
            } catch { case _: Exception => -1 }
        if (read < 4) Unknown
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

    /** Why `file`, sitting under `platformKey`, does not belong there; `None` when it does.
      *
      * Both halves matter. The format check catches a foreign binary under a key it cannot load on. The
      * extension check catches the same file renamed to fit, which is what a copy step keyed on the build
      * host rather than on the artifact produces.
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
        extMismatch.orElse(formatMismatch)
    }
}
