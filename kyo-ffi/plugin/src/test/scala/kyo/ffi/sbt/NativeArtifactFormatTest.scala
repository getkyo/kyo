package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the packaging check's format reader.
  *
  * The failure it exists for shipped once: a macOS binding was published as `libmachine_macos.so` under
  * `META-INF/native/linux-x86_64/`, and no darwin artifact was published at all. The directory, the file name
  * and the extension were all self-consistent there, so only the bytes inside distinguish a correctly filed
  * artifact from a mis-filed one.
  */
class NativeArtifactFormatTest extends AnyFunSuite with Matchers {

    private def fileWith(name: String, magic: Array[Int]): File = {
        val dir = Files.createTempDirectory("kyo-ffi-format").toFile
        dir.deleteOnExit()
        val f = new File(dir, name)
        Files.write(f.toPath, (magic.map(_.toByte) ++ Array.fill[Byte](64)(0)))
        f.deleteOnExit()
        f
    }

    private val elfMagic   = Array(0x7f, 'E'.toInt, 'L'.toInt, 'F'.toInt)
    private val machoMagic = Array(0xcf, 0xfa, 0xed, 0xfe)
    private val fatMagic   = Array(0xca, 0xfe, 0xba, 0xbe)
    private val peMagic    = Array('M'.toInt, 'Z'.toInt, 0x90, 0x00)

    test("reads the object format from the leading bytes") {
        NativeArtifactFormat.of(fileWith("libx.so", elfMagic)) shouldBe NativeArtifactFormat.Elf
        NativeArtifactFormat.of(fileWith("libx.dylib", machoMagic)) shouldBe NativeArtifactFormat.MachO
        NativeArtifactFormat.of(fileWith("libx.dylib", fatMagic)) shouldBe NativeArtifactFormat.MachO
        NativeArtifactFormat.of(fileWith("x.dll", peMagic)) shouldBe NativeArtifactFormat.Pe
        NativeArtifactFormat.of(fileWith("libx.so", Array(0, 0, 0, 0))) shouldBe NativeArtifactFormat.Unknown
    }

    test("a correctly filed artifact reports no mismatch") {
        NativeArtifactFormat.mismatch("linux-x86_64", fileWith("libx.so", elfMagic)) shouldBe None
        NativeArtifactFormat.mismatch("linux-musl-aarch64", fileWith("libx.so", elfMagic)) shouldBe None
        NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libx.dylib", machoMagic)) shouldBe None
        NativeArtifactFormat.mismatch("windows-x86_64", fileWith("x.dll", peMagic)) shouldBe None
    }

    test("a Mach-O object under a linux key is a mismatch") {
        val m = NativeArtifactFormat.mismatch("linux-x86_64", fileWith("libmachine_macos.so", machoMagic))
        m.isDefined shouldBe true
        m.get should include("Mach-O")
        m.get should include("linux-x86_64")
    }

    test("an ELF object under a darwin key is a mismatch even when the extension is right") {
        val m = NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libmachine_macos.dylib", elfMagic))
        m.isDefined shouldBe true
        m.get should include("ELF")
    }

    test("a wrong extension is a mismatch before the bytes are even read") {
        val m = NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libmachine_macos.so", machoMagic))
        m.isDefined shouldBe true
        m.get should include("'so'")
        m.get should include("'dylib'")
    }

    test("an unrecognized platform key asserts nothing") {
        NativeArtifactFormat.expectedFormat("solaris-sparc") shouldBe None
        NativeArtifactFormat.expectedExtension("solaris-sparc") shouldBe None
        NativeArtifactFormat.mismatch("solaris-sparc", fileWith("libx.so", elfMagic)) shouldBe None
    }

    test("library id is read back off the canonical artifact name") {
        NativeArtifactFormat.libraryIdOf("libmachine_macos.dylib") shouldBe Some("machine_macos")
        NativeArtifactFormat.libraryIdOf("libkyonet_boringssl.so") shouldBe Some("kyonet_boringssl")
        NativeArtifactFormat.libraryIdOf("kyo_tcp.dll") shouldBe Some("kyo_tcp")
        NativeArtifactFormat.libraryIdOf("noextension") shouldBe None
    }
}

/** The shared-pool staging rule.
  *
  * `ffiPrebuiltDir` is strict on purpose: a native for an id the project does not declare, in a directory
  * pointed at that project deliberately, is a mistake worth failing on. `ffiPrebuiltPool` is the opposite
  * situation, a directory several FFI modules share, where another module's native is the normal case. The
  * two rules have to differ, and this pins that they do.
  */
class FfiPrebuiltPoolTest extends AnyFunSuite with Matchers {

    private def idOf(name: String): Option[String] = CCompiler.parseArtifactName(name).map(_._1)

    /** What the pool staging does: keep the files whose declared id this project owns. */
    private def taken(pool: Seq[String], declared: Set[String]): Seq[String] =
        pool.filter(n => idOf(n).exists(declared.contains))

    private val pool = Seq(
        "libmachine_macos-darwin-aarch64.dylib",
        "libmachine_macos-darwin-x86_64.dylib",
        "libkyo_net-darwin-aarch64.dylib",
        "libkyo_boringssl-darwin-aarch64.dylib"
    )

    test("a pooled native is attributed to the library id its filename declares") {
        idOf("libmachine_macos-darwin-aarch64.dylib") shouldBe Some("machine_macos")
        idOf("libkyo_net-darwin-aarch64.dylib") shouldBe Some("kyo_net")
    }

    test("a project takes its own natives out of a shared pool and leaves the others") {
        taken(pool, Set("machine_macos")) shouldBe Seq(
            "libmachine_macos-darwin-aarch64.dylib",
            "libmachine_macos-darwin-x86_64.dylib"
        )
        taken(pool, Set("kyo_net", "kyo_boringssl")) should have size 2
    }

    test("a project whose natives no producer built takes nothing, rather than failing on another module's") {
        // This is the whole reason the pool cannot reuse ffiPrebuiltDir's rule: on a Linux producer the
        // darwin-only shim is absent and every file in the pool belongs to someone else.
        taken(pool, Set("not_built_here")) shouldBe empty
    }

    test("a name that does not parse is still not attributed to anyone, so a typo cannot hide in the pool") {
        idOf("machine_macos.dylib") shouldBe None
        idOf("libmachine_macos-darwin.dylib") shouldBe None
        taken(Seq("machine_macos.dylib"), Set("machine_macos")) shouldBe empty
    }
}

/** The architecture half of the packaging check. An ignored cross-compilation flag produces an
  * artifact that is right about its OS and wrong about its CPU, which every layout-based check and
  * the format probe both pass. Nothing else in the pipeline sees it: the vendored-library scripts
  * assert the third-party archive they stage, not the shim a producer links from it, and a prebuilt
  * arriving through the shared pool passes no script at all.
  */
class NativeArtifactArchTest extends AnyFunSuite with Matchers {

    private def write(name: String, bytes: Array[Byte]): File = {
        val dir = Files.createTempDirectory("kyo-ffi-arch").toFile
        dir.deleteOnExit()
        val f = new File(dir, name)
        Files.write(f.toPath, bytes)
        f.deleteOnExit()
        f
    }

    private def pad(b: Array[Int], to: Int): Array[Byte] =
        (b.map(_.toByte) ++ Array.fill[Byte](math.max(0, to - b.length))(0))

    /** ELF64: magic, EI_CLASS=2, EI_DATA (1 little / 2 big), then e_machine at offset 18. */
    private def elf(machine: Int, littleEndian: Boolean = true): Array[Byte] = {
        val h = Array.fill(64)(0)
        h(0) = 0x7f; h(1) = 'E'; h(2) = 'L'; h(3) = 'F'
        h(4) = 2
        h(5) = if (littleEndian) 1 else 2
        if (littleEndian) { h(18) = machine & 0xff; h(19) = (machine >> 8) & 0xff }
        else { h(18) = (machine >> 8) & 0xff; h(19) = machine & 0xff }
        pad(h, 64)
    }

    /** Mach-O 64 little-endian: magic cffaedfe, then cputype in the next four bytes. */
    private def macho(cpuType: Long): Array[Byte] = {
        val h = Array.fill(64)(0)
        h(0) = 0xcf; h(1) = 0xfa; h(2) = 0xed; h(3) = 0xfe
        h(4) = (cpuType & 0xff).toInt
        h(5) = ((cpuType >> 8) & 0xff).toInt
        h(6) = ((cpuType >> 16) & 0xff).toInt
        h(7) = ((cpuType >> 24) & 0xff).toInt
        pad(h, 64)
    }

    /** Fat Mach-O: a magic, a big-endian slice count, then one cputype per slice record. `stride` is
      * 20 for fat_arch (FAT_MAGIC) and 32 for fat_arch_64 (FAT_MAGIC_64, whose offset and size fields
      * are 64-bit).
      */
    private def fatWith(magicLow: Int, stride: Int, cpuTypes: Seq[Long]): Array[Byte] = {
        val h = Array.fill(8 + cpuTypes.size * stride + 16)(0)
        h(0) = 0xca; h(1) = 0xfe; h(2) = 0xba; h(3) = magicLow
        h(7) = cpuTypes.size
        cpuTypes.zipWithIndex.foreach { case (cpu, i) =>
            val o = 8 + i * stride
            h(o) = ((cpu >> 24) & 0xff).toInt
            h(o + 1) = ((cpu >> 16) & 0xff).toInt
            h(o + 2) = ((cpu >> 8) & 0xff).toInt
            h(o + 3) = (cpu & 0xff).toInt
        }
        pad(h, h.length)
    }

    private def fat(cpuTypes: Seq[Long]): Array[Byte]   = fatWith(0xbe, 20, cpuTypes)
    private def fat64(cpuTypes: Seq[Long]): Array[Byte] = fatWith(0xbf, 32, cpuTypes)

    /** A big-endian host's view of a fat header: the magic bytes reversed. */
    private def swappedFat(): Array[Byte] = {
        val h = Array.fill(64)(0)
        h(0) = 0xbe; h(1) = 0xba; h(2) = 0xfe; h(3) = 0xca
        h(4) = 2 // would be read as a cputype by a thin-header reader
        pad(h, 64)
    }

    /** PE: MZ, e_lfanew at 0x3c, then "PE\0\0" and the machine word. */
    private def pe(machine: Int, peOffset: Int = 0x80): Array[Byte] = {
        val h = Array.fill(peOffset + 8)(0)
        h(0) = 'M'; h(1) = 'Z'
        h(0x3c) = peOffset & 0xff
        h(0x3d) = (peOffset >> 8) & 0xff
        h(peOffset) = 'P'; h(peOffset + 1) = 'E'
        h(peOffset + 4) = machine & 0xff
        h(peOffset + 5) = (machine >> 8) & 0xff
        pad(h, h.length)
    }

    private val ElfX86     = 0x3e
    private val ElfAarch64 = 0xb7
    private val CpuX86_64  = 0x01000007L
    private val CpuArm64   = 0x0100000cL
    private val PeAmd64    = 0x8664
    private val PeArm64    = 0xaa64

    test("reads the architecture out of an ELF header, either endianness") {
        NativeArtifactFormat.archOf(write("libx.so", elf(ElfX86))) shouldBe Some("x86_64")
        NativeArtifactFormat.archOf(write("libx.so", elf(ElfAarch64))) shouldBe Some("aarch64")
        NativeArtifactFormat.archOf(write("libx.so", elf(ElfAarch64, littleEndian = false))) shouldBe Some("aarch64")
    }

    test("reads the architecture out of a Mach-O header, thin and fat") {
        NativeArtifactFormat.archOf(write("libx.dylib", macho(CpuX86_64))) shouldBe Some("x86_64")
        NativeArtifactFormat.archOf(write("libx.dylib", macho(CpuArm64))) shouldBe Some("aarch64")
        NativeArtifactFormat.archOf(write("libx.dylib", fat(Seq(CpuArm64)))) shouldBe Some("aarch64")
        NativeArtifactFormat.archOf(write("libx.dylib", fat(Seq(CpuX86_64, CpuArm64)))) shouldBe Some("x86_64")
    }

    test("reads the architecture out of a PE header") {
        NativeArtifactFormat.archOf(write("x.dll", pe(PeAmd64))) shouldBe Some("x86_64")
        NativeArtifactFormat.archOf(write("x.dll", pe(PeArm64))) shouldBe Some("aarch64")
    }

    test("a machine value outside the supported set reads as foreign, not as unreadable") {
        // These two answers must stay distinct: an unreadable header is nothing to assert, while a
        // header that parses and names another CPU is an artifact for a platform nothing here builds
        // for. Collapsing them into "no architecture" is what would let such a file through.
        NativeArtifactFormat.archReadingOf(write("libx.so", elf(0x28))) shouldBe
            NativeArtifactFormat.ArchForeign("ELF machine 0x28") // EM_ARM, 32-bit
        NativeArtifactFormat.archReadingOf(write("libx.dylib", macho(0x7L))) shouldBe
            NativeArtifactFormat.ArchForeign("Mach-O cputype 0x7") // CPU_TYPE_X86, 32-bit
        NativeArtifactFormat.archOf(write("libx.so", elf(0x28))) shouldBe None
    }

    test("a header that does not parse reads as unreadable") {
        NativeArtifactFormat.archReadingOf(write("libx.so", Array[Byte](0, 0, 0, 0))) shouldBe
            NativeArtifactFormat.ArchUnreadable
        // A PE whose e_lfanew points past the header window has nothing readable at the machine word.
        val truncated = Array.fill[Byte](64)(0)
        truncated(0) = 'M'; truncated(1) = 'Z'
        NativeArtifactFormat.archReadingOf(write("x.dll", truncated)) shouldBe NativeArtifactFormat.ArchUnreadable
    }

    test("a fat64 Mach-O is walked at its own 32-byte stride") {
        // fat_arch is 20 bytes; fat_arch_64 is 32, because its offset and size fields are 64-bit.
        // Walking a fat64 header at stride 20 reads garbage for every slice after the first, which a
        // single-slice fixture cannot show.
        NativeArtifactFormat.archOf(write("libx.dylib", fat64(Seq(CpuX86_64, CpuArm64)))) shouldBe Some("x86_64")
        NativeArtifactFormat.archOf(write("libx.dylib", fat64(Seq(CpuArm64, CpuX86_64)))) shouldBe Some("aarch64")
        // The second slice of a two-slice fat64 is only reachable at the right stride.
        NativeArtifactFormat.archReadingOf(write("libx.dylib", fat64(Seq(0x7L, CpuArm64)))) shouldBe
            NativeArtifactFormat.ArchKnown("aarch64")
    }

    test("a byte-swapped fat header is reported unreadable, never guessed") {
        // Reading one as a thin header would take its slice count for a cputype. No toolchain here
        // produces one, so it is refused rather than interpreted.
        NativeArtifactFormat.archReadingOf(write("libx.dylib", swappedFat())) shouldBe
            NativeArtifactFormat.ArchUnreadable
    }

    test("a right-OS wrong-CPU artifact is a mismatch") {
        // A cross flag the build ignored: the artifact is the host's arch under the requested arch's
        // name. Format and extension both agree; only the CPU does not.
        NativeArtifactFormat.mismatch("darwin-x86_64", write("libx.dylib", macho(CpuArm64))) shouldBe
            Some("darwin-x86_64/libx.dylib is a aarch64 object where darwin-x86_64 requires x86_64")
        NativeArtifactFormat.mismatch("linux-aarch64", write("libx.so", elf(ElfX86))) shouldBe
            Some("linux-aarch64/libx.so is a x86_64 object where linux-aarch64 requires aarch64")
        NativeArtifactFormat.mismatch("windows-aarch64", write("x.dll", pe(PeAmd64))) shouldBe
            Some("windows-aarch64/x.dll is a x86_64 object where windows-aarch64 requires aarch64")
    }

    test("a correctly filed artifact reports no mismatch on any axis") {
        NativeArtifactFormat.mismatch("darwin-aarch64", write("libx.dylib", macho(CpuArm64))) shouldBe None
        NativeArtifactFormat.mismatch("linux-x86_64", write("libx.so", elf(ElfX86))) shouldBe None
        NativeArtifactFormat.mismatch("linux-musl-aarch64", write("libx.so", elf(ElfAarch64))) shouldBe None
        NativeArtifactFormat.mismatch("windows-x86_64", write("x.dll", pe(PeAmd64))) shouldBe None
    }

    test("a header that names no machine is unreadable, and not a failure") {
        // Each format has a sentinel for "no machine named": ELF EM_NONE, Mach-O cputype 0, PE
        // IMAGE_FILE_MACHINE_UNKNOWN. That is the header declining to say, so the check asserts
        // nothing on it; refusing to package a stripped or unusual object would turn a check into an
        // obstacle. The format and extension halves still apply, so this is not a hole.
        NativeArtifactFormat.archReadingOf(write("libx.so", elf(0))) shouldBe NativeArtifactFormat.ArchUnreadable
        NativeArtifactFormat.archReadingOf(write("libx.dylib", macho(0L))) shouldBe NativeArtifactFormat.ArchUnreadable
        NativeArtifactFormat.archReadingOf(write("x.dll", pe(0))) shouldBe NativeArtifactFormat.ArchUnreadable
        NativeArtifactFormat.mismatch("linux-x86_64", write("libx.so", elf(0))) shouldBe None
    }

    test("a foreign CPU under a supported platform key IS a failure") {
        // Distinct from an unreadable header: this one parses and names a CPU nothing here builds
        // for, so packaging it under a supported key is a mistake the check must report.
        NativeArtifactFormat.mismatch("linux-x86_64", write("libx.so", elf(0x28))) shouldBe
            Some("linux-x86_64/libx.so has ELF machine 0x28, which is not x86_64 as linux-x86_64 requires")
        NativeArtifactFormat.mismatch("darwin-aarch64", write("libx.dylib", macho(0x7L))) shouldBe
            Some("darwin-aarch64/libx.dylib has Mach-O cputype 0x7, which is not aarch64 as darwin-aarch64 requires")
    }

    test("glibc and musl are indistinguishable, and that limit is deliberate") {
        // Identical ELF headers, so this axis rests on producer discipline rather than on the check.
        NativeArtifactFormat.mismatch("linux-musl-x86_64", write("libx.so", elf(ElfX86))) shouldBe None
        NativeArtifactFormat.mismatch("linux-x86_64", write("libx.so", elf(ElfX86))) shouldBe None
    }
}
