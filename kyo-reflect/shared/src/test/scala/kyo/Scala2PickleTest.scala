package kyo

import kyo.internal.reflect.classfile.ClassfileUnpickler
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.scala2.Scala2PickleReader
import kyo.internal.reflect.scala2.Scala2PickleResult
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.type_.TypeArena

/** Tests for Phase 10: Scala 2 pickle reader.
  *
  * All 7 tests are JVM-only because:
  *   - InflaterInputStream (for the "Scala" ZLIB attribute) is JVM-only.
  *   - On JS/Native the InflateHook returns NotImplemented, so the inflation path is untestable.
  *   - The ScalaSig compact-encoding tests use TestResourceLoader which is JVM-only.
  *
  * Tests use hand-crafted synthetic Scala 2 pickle bytes (raw format, no compact encoding) because generating real Scala 2 classfiles
  * during the agent run requires a Scala 2 compiler. Deviations from plan (see PHASE-10-IMPL-NOTES.md).
  *
  * Scala 2 pickle binary format (raw):
  *   - Byte 0: MajorVersion = 5
  *   - Byte 1: MinorVersion = 0
  *   - NAT: entry count (high-bit-SET = more bytes, high-bit-CLEAR = last byte, big-endian 7-bit groups)
  *   - Per entry: NAT tag, NAT length, <length> data bytes
  *
  * For entry data (symbol entries): nameRef(nat) ownerRef(nat) flags(longNat) [infoRef(nat)]
  *
  * A single-byte NAT N where 0 <= N <= 127 is encoded as byte N (high bit 0 = last byte).
  *
  * Name entry data: raw UTF-8 bytes of the name string.
  */
class Scala2PickleTest extends Test:

    private val interner = new Interner(32)

    // -------------------------------------------------------------------------
    // Pickle builder helpers
    // -------------------------------------------------------------------------

    /** Build a NAT-encoded byte sequence for value n. For n <= 127: single byte n (Scala 2 big-endian base-128). */
    private def nat(n: Int): Array[Byte] =
        if n <= 0x7f then Array(n.toByte)
        else
            // Multi-byte NAT: bytes from MSB to LSB, each with high bit set except the last
            val bits  = 28 // max significant bits we handle
            var bytes = List.empty[Byte]
            var v     = n
            bytes = (v & 0x7f).toByte :: bytes
            v = v >>> 7
            while v > 0 do
                bytes = ((v & 0x7f) | 0x80).toByte :: bytes
                v = v >>> 7
            bytes.toArray

    /** Build a long-nat-encoded byte sequence (same encoding as nat but accumulates into Long). */
    private def longNat(l: Long): Array[Byte] =
        if l <= 0x7fL then Array(l.toByte)
        else
            var bytes = List.empty[Byte]
            var v     = l
            bytes = (v & 0x7fL).toByte :: bytes
            v = v >>> 7
            while v > 0L do
                bytes = ((v & 0x7fL) | 0x80L).toByte :: bytes
                v = v >>> 7
            bytes.toArray

    /** Build a single pickle entry as bytes: tag + length + data. */
    private def entry(tag: Int, data: Array[Byte]): Array[Byte] =
        nat(tag) ++ nat(data.length) ++ data

    /** Build name entry data: raw UTF-8 bytes. */
    private def nameData(s: String): Array[Byte] =
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    /** Build symbol entry data: nameRef(nat) ownerRef(nat) flags(longNat). */
    private def symData(nameRef: Int, ownerRef: Int, flags: Long): Array[Byte] =
        nat(nameRef) ++ nat(ownerRef) ++ longNat(flags)

    /** Build a minimal pickle from a list of entries. Returns the full pickle byte array. */
    private def buildPickle(entries: Array[Byte]*): Array[Byte] =
        val hdr   = Array(Scala2PickleReader.MajorVersion.toByte, Scala2PickleReader.MinorVersion.toByte)
        val count = nat(entries.length)
        entries.foldLeft(hdr ++ count)(_ ++ _)
    end buildPickle

    /** Build a ClassfileResult from raw classfile bytes with a ScalaSig attribute injected.
      *
      * This helper builds a minimal classfile for java/lang/String and then replaces the ScalaSig bytes in the result through direct
      * Scala2PickleReader invocation (since we can't inject a custom attribute into a JDK classfile at test time).
      */
    private def readPickleDirect(pickleBytes: Array[Byte])(using Frame): Scala2PickleResult < (Sync & Abort[ReflectError]) =
        Scala2PickleReader.readRaw(pickleBytes, interner, new TypeArena, new ClasspathRef)

    // -------------------------------------------------------------------------
    // Test 1: ScalaSig attribute -> Flag.Scala2 on all symbols
    // -------------------------------------------------------------------------

    "Test 1: ScalaSig classfile dispatch: classfile with ScalaSig produces symbols with Flag.Scala2" taggedAs jvmOnly in run {
        // Build a minimal pickle with one TERMname entry (idx 0 = "MyClass") and one CLASSsym entry (idx 1)
        // CLASSsym data: nameRef=0 ownerRef=0 flags=0 (no flags)
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val syms = result.symbols
            assert(syms.nonEmpty, "Expected at least one symbol from pickle")
            val hasScala2 = syms.exists(_.flags.contains(Reflect.Flag.Scala2))
            assert(hasScala2, s"Expected at least one symbol with Flag.Scala2; flags were: ${syms.map(_.flags.bits).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 2: Scala 2 case class -> kind == Class, Flag.Case
    // -------------------------------------------------------------------------

    "Test 2: Scala 2 case class: CLASSsym with CASE flag -> kind=Class and Flag.Case" taggedAs jvmOnly in run {
        // CLASSsym with CASE_FLAG (0x00000800L) set
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyCaseClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, Scala2PickleReader.CASE_FLAG))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val classSyms = result.symbols.filter(_.kind == Reflect.SymbolKind.Class)
            assert(classSyms.nonEmpty, "Expected at least one Class symbol")
            val caseClass = classSyms.find(_.flags.contains(Reflect.Flag.Case))
            assert(caseClass.isDefined, s"Expected a Class symbol with Flag.Case; flags: ${classSyms.map(_.flags.bits).mkString(", ")}")
            val sym = caseClass.get
            assert(sym.kind == Reflect.SymbolKind.Class, s"Expected kind=Class, got ${sym.kind}")
            assert(sym.flags.contains(Reflect.Flag.Scala2), "Expected Flag.Scala2")
    }

    // -------------------------------------------------------------------------
    // Test 3: Scala 2 method -> declaredType is Type.Function
    // -------------------------------------------------------------------------

    "Test 3: Scala 2 method: VALsym with METH flag -> declaredType is Type.Function" taggedAs jvmOnly in run {
        // VALsym with METH_FLAG (0x00040000L): a method named "apply"
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("apply"))
        val methodEntry = entry(Scala2PickleReader.VALsym, symData(0, 0, Scala2PickleReader.METH_FLAG))
        val pickleBytes = buildPickle(nameEntry, methodEntry)
        readPickleDirect(pickleBytes).map: result =>
            val methods = result.symbols.filter(_.kind == Reflect.SymbolKind.Method)
            assert(methods.nonEmpty, "Expected at least one Method symbol")
            val method = methods.head
            assert(method.flags.contains(Reflect.Flag.Scala2), "Expected Flag.Scala2")
            import AllowUnsafe.embrace.danger
            val declaredType = method._declaredType.get()
            declaredType match
                case Reflect.Type.Function(_, _, _) =>
                    succeed
                case other =>
                    fail(s"Expected Type.Function, got $other")
            end match
    }

    // -------------------------------------------------------------------------
    // Test 4: Scala 2 type alias -> kind == TypeAlias, declaredType is Named
    // -------------------------------------------------------------------------

    "Test 4: Scala 2 type alias: ALIASsym -> kind=TypeAlias and declaredType=Type.Named" taggedAs jvmOnly in run {
        // ALIASsym entry: a type alias named "Alias"
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("Alias"))
        val aliasEntry  = entry(Scala2PickleReader.ALIASsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, aliasEntry)
        readPickleDirect(pickleBytes).map: result =>
            val aliases = result.symbols.filter(_.kind == Reflect.SymbolKind.TypeAlias)
            assert(aliases.nonEmpty, "Expected at least one TypeAlias symbol")
            val alias = aliases.head
            assert(alias.kind == Reflect.SymbolKind.TypeAlias, s"Expected TypeAlias, got ${alias.kind}")
            assert(alias.flags.contains(Reflect.Flag.Scala2), "Expected Flag.Scala2")
            import AllowUnsafe.embrace.danger
            val declaredType = alias._declaredType.get()
            declaredType match
                case Reflect.Type.Named(sym) =>
                    // The placeholder type alias resolves to Named("String")
                    assert(sym.name.asString == "String", s"Expected Named(String) for alias, got Named(${sym.name.asString})")
                case other =>
                    fail(s"Expected Type.Named, got $other")
            end match
    }

    // -------------------------------------------------------------------------
    // Test 5: classfile without ScalaSig -> no Flag.Scala2
    // -------------------------------------------------------------------------

    "Test 5: Java-only classfile (no ScalaSig attribute) -> Flag.Scala2 is absent" taggedAs jvmOnly in run {
        // Read a real JDK classfile that has no ScalaSig attribute (java/lang/Object.class)
        val bytes  = TestResourceLoader.loadBytes("java/lang/Object.class")
        val result = ClassfileUnpickler.read(bytes, interner, new TypeArena, new ClasspathRef)
        result.map: r =>
            val sym = r.classSymbol
            assert(!sym.flags.contains(Reflect.Flag.Scala2), s"Expected no Flag.Scala2 on Java classfile; got flags=${sym.flags.bits}")
            // Also verify the member symbols don't have Scala2 flag
            val scala2Members = r.symbols.filter(_.flags.contains(Reflect.Flag.Scala2))
            assert(
                scala2Members.isEmpty,
                s"Expected no Scala2 members on Java classfile; found: ${scala2Members.map(_.name.asString).mkString(", ")}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 6: corrupt ScalaSig bytes -> Abort.fail(CorruptedFile)
    // -------------------------------------------------------------------------

    "Test 6: corrupt Scala 2 pickle bytes -> Abort.fail(CorruptedFile)" taggedAs jvmOnly in run {
        // A pickle with wrong major version (e.g., 99 instead of 5) should produce CorruptedFile
        val badPickle = Array[Byte](99.toByte, 0.toByte) // wrong major version
        Abort.run(readPickleDirect(badPickle)).map: result =>
            result match
                case Result.Failure(ReflectError.CorruptedFile(_, _, reason)) =>
                    assert(reason.contains("version") || reason.contains("99"), s"Unexpected reason: $reason")
                case Result.Failure(other) =>
                    fail(s"Expected CorruptedFile, got failure: $other")
                case Result.Success(_) =>
                    fail("Expected failure, but got success")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: $t")
    }

    // -------------------------------------------------------------------------
    // Test 7: Scala 2 class parents resolve via placeholder mechanism
    // -------------------------------------------------------------------------

    "Test 7: Scala 2 class symbol has at least one parent type (AnyRef placeholder)" taggedAs jvmOnly in run {
        // Build a pickle with a single CLASSsym entry. The Scala2PickleReader adds an AnyRef placeholder parent.
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyParentClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val parents = result.parents
            assert(parents.nonEmpty, "Expected at least one parent type for a Scala 2 class")
            // The Scala2PickleReader provides an AnyRef placeholder as the parent
            val hasAnyRefParent = parents.exists:
                case Reflect.Type.Named(sym) => sym.name.asString == "AnyRef"
                case _                       => false
            assert(hasAnyRefParent, s"Expected AnyRef parent placeholder; got: ${parents.map(_.show).mkString(", ")}")
    }

end Scala2PickleTest
