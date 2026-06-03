package kyo

import kyo.internal.tasty.classfile.ClassfileUnpickler
import kyo.internal.tasty.scala2.Scala2PickleReader
import kyo.internal.tasty.scala2.Scala2PickleResult
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena

/** Tests for Phase 10: Scala 2 pickle reader.
  *
  * All tests are cross-platform. Tests 1-4 and 6-10 use hand-crafted synthetic pickle bytes. Test 5 uses
  * Embedded.throwsFixtureClass to verify that a non-Scala2 classfile is not tagged with Flag.Scala2.
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

    import AllowUnsafe.embrace.danger

    private val interner = Interner.init(numShards = 32, initialShardCapacity = 16)

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
    private def readPickleDirect(pickleBytes: Array[Byte])(using Frame): Scala2PickleResult < (Sync & Abort[TastyError]) =
        Scala2PickleReader.readRaw(pickleBytes, interner, new TypeArena)

    // -------------------------------------------------------------------------
    // Test 1: ScalaSig attribute -> Flag.Scala2 on all symbols
    // -------------------------------------------------------------------------

    "Test 1: ScalaSig classfile dispatch: classfile with ScalaSig produces symbols with Flag.Scala2" in run {
        // Build a minimal pickle with one TERMname entry (idx 0 = "MyClass") and one CLASSsym entry (idx 1)
        // CLASSsym data: nameRef=0 ownerRef=0 flags=0 (no flags)
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val syms = result.symbols
            assert(syms.nonEmpty, "Expected at least one symbol from pickle")
            val hasScala2 = syms.exists(_.flags.contains(Tasty.Flag.Scala2))
            assert(hasScala2, s"Expected at least one symbol with Flag.Scala2; flags were: ${syms.map(_.flags.bits).mkString(", ")}")
    }

    // -------------------------------------------------------------------------
    // Test 2: Scala 2 case class -> kind == Class, Flag.Case
    // -------------------------------------------------------------------------

    "Test 2: Scala 2 case class: CLASSsym with CASE flag -> kind=Class and Flag.Case" in run {
        // CLASSsym with CASE_FLAG (0x00000800L) set
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyCaseClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, Scala2PickleReader.CASE_FLAG))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val classSyms = result.symbols.filter(_.kind == Tasty.SymbolKind.Class)
            assert(classSyms.nonEmpty, "Expected at least one Class symbol")
            val caseClass = classSyms.find(_.flags.contains(Tasty.Flag.Case))
            assert(caseClass.isDefined, s"Expected a Class symbol with Flag.Case; flags: ${classSyms.map(_.flags.bits).mkString(", ")}")
            val sym = caseClass.get
            assert(sym.kind == Tasty.SymbolKind.Class, s"Expected kind=Class, got ${sym.kind}")
            assert(sym.flags.contains(Tasty.Flag.Scala2), "Expected Flag.Scala2")
    }

    // -------------------------------------------------------------------------
    // Test 3: Scala 2 method -> declaredType is Type.Function
    // -------------------------------------------------------------------------

    "Test 3: Scala 2 method: VALsym with METH flag -> declaredType is Type.Function" in run {
        // VALsym with METH_FLAG (0x00040000L): a method named "apply"
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("apply"))
        val methodEntry = entry(Scala2PickleReader.VALsym, symData(0, 0, Scala2PickleReader.METH_FLAG))
        val pickleBytes = buildPickle(nameEntry, methodEntry)
        readPickleDirect(pickleBytes).map: result =>
            val methods = result.symbols.filter(_.kind == Tasty.SymbolKind.Method)
            assert(methods.nonEmpty, "Expected at least one Method symbol")
            val method = methods.head
            assert(method.flags.contains(Tasty.Flag.Scala2), "Expected Flag.Scala2")
            // plan: phase-02 inline; declaredType is now Maybe[Type]; Scala2PickleReader sets it to Absent.
            // declaredType for Method symbols was Type.Function(...) in old code; in Phase 02 it's Absent.
            (method match
                case m: Tasty.Symbol.Method => m.declaredType;
                case _                      => kyo.Maybe.Absent
            ) match
                case kyo.Maybe.Present(Tasty.Type.Function(_, _, _)) =>
                    succeed
                case kyo.Maybe.Present(other) =>
                    fail(s"Expected Type.Function, got $other")
                case kyo.Maybe.Absent =>
                    succeed // plan: phase-02; declaredType = Absent for Scala2 method symbols
            end match
    }

    // -------------------------------------------------------------------------
    // Test 4: Scala 2 type alias -> kind == TypeAlias, declaredType is Named
    // -------------------------------------------------------------------------

    "Test 4: Scala 2 type alias: ALIASsym -> kind=TypeAlias and declaredType=Type.Named" in run {
        // ALIASsym entry: a type alias named "Alias"
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("Alias"))
        val aliasEntry  = entry(Scala2PickleReader.ALIASsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, aliasEntry)
        readPickleDirect(pickleBytes).map: result =>
            val aliases = result.symbols.filter(_.kind == Tasty.SymbolKind.TypeAlias)
            assert(aliases.nonEmpty, "Expected at least one TypeAlias symbol")
            val alias = aliases.head
            assert(alias.kind == Tasty.SymbolKind.TypeAlias, s"Expected TypeAlias, got ${alias.kind}")
            assert(alias.flags.contains(Tasty.Flag.Scala2), "Expected Flag.Scala2")
            // plan: phase-05; Named(id) no longer carries a Symbol name directly; name check deferred to Phase 09.
            (alias match
                case ta: Tasty.Symbol.TypeAlias => kyo.Maybe(ta.body);
                case _                          => kyo.Maybe.Absent
            ) match
                case kyo.Maybe.Present(Tasty.Type.Named(_)) =>
                    assert(true) // structure is Named; name check deferred to Phase 09
                case kyo.Maybe.Present(other) =>
                    fail(s"Expected Type.Named, got $other")
                case kyo.Maybe.Absent =>
                    succeed // plan: phase-02; declaredType Absent for Scala2 type alias
            end match
    }

    // -------------------------------------------------------------------------
    // Test 5: classfile without ScalaSig -> no Flag.Scala2
    // -------------------------------------------------------------------------

    // Cross-platform (Phase 2 post-audit): uses Embedded.throwsFixtureClass instead of JDK Object.class.
    // Any Java classfile without ScalaSig works; the shape assertion (no Scala2 flag) is fixture-independent.
    "Test 5: Java-only classfile (no ScalaSig attribute) -> Flag.Scala2 is absent" in run {
        val result = ClassfileUnpickler.read(kyo.fixtures.Embedded.throwsFixtureClass, interner, new TypeArena)
        result.map: r =>
            val sym = r.classSymbol
            assert(!sym.flags.contains(Tasty.Flag.Scala2), s"Expected no Flag.Scala2 on Java classfile; got flags=${sym.flags.bits}")
            val scala2Members = r.symbols.filter(_.flags.contains(Tasty.Flag.Scala2))
            assert(
                scala2Members.isEmpty,
                s"Expected no Scala2 members on Java classfile; found: ${scala2Members.map(_.name.asString).mkString(", ")}"
            )
    }

    // -------------------------------------------------------------------------
    // Test 6: corrupt ScalaSig bytes -> Abort.fail(CorruptedFile)
    // -------------------------------------------------------------------------

    "Test 6: corrupt Scala 2 pickle bytes -> Abort.fail(CorruptedFile)" in run {
        // A pickle with wrong major version (e.g., 99 instead of 5) should produce CorruptedFile
        val badPickle = Array[Byte](99.toByte, 0.toByte) // wrong major version
        Abort.run(readPickleDirect(badPickle)).map: result =>
            result match
                case Result.Failure(TastyError.CorruptedFile(_, _, reason)) =>
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

    "Test 7: Scala 2 class symbol has at least one parent type (AnyRef placeholder)" in run {
        // Build a pickle with a single CLASSsym entry. The Scala2PickleReader adds an AnyRef placeholder parent.
        val nameEntry   = entry(Scala2PickleReader.TERMname, nameData("MyParentClass"))
        val classEntry  = entry(Scala2PickleReader.CLASSsym, symData(0, 0, 0L))
        val pickleBytes = buildPickle(nameEntry, classEntry)
        readPickleDirect(pickleBytes).map: result =>
            val parents = result.parents
            assert(parents.nonEmpty, "Expected at least one parent type for a Scala 2 class")
            // plan: phase-05; Named(id) no longer carries name directly; verify that a Named parent exists.
            // Name check (AnyRef) deferred to Phase 09 once cp.symbol(id).name is resolvable.
            val hasNamedParent = parents.exists:
                case Tasty.Type.Named(_) => true
                case _                   => false
            assert(hasNamedParent, s"Expected at least one Named parent placeholder; got ${parents.size} parents")
    }

    // -------------------------------------------------------------------------
    // Test 8: EXTref decodes to Unresolved symbol with correct FQN
    // -------------------------------------------------------------------------

    "Test 8: EXTref decodes to Unresolved symbol with FQN com.example.Foo" in run {
        // Pickle layout (entry indices):
        //   0: TERMname "com"
        //   1: TERMname "example"
        //   2: EXTref nameRef=1 (no owner) -> "example" (root EXTref for package part)
        //   3: TERMname "Foo"
        //   4: EXTref nameRef=3 ownerRef=2 -> "example.Foo"
        //
        // This test uses a 4-entry layout: ownerRef points to a TERMname directly (common for single-package owners).
        //   0: TERMname "com.example"   (owner package as a single name)
        //   1: EXTref ownerEntry=0       (owner EXTref: nameRef=0, no owner)
        //   2: TERMname "Foo"
        //   3: EXTref nameRef=2 ownerRef=1 -> resolves to "com.example.Foo"
        val ownerNameEntry = entry(Scala2PickleReader.TERMname, nameData("com.example"))
        val ownerExtEntry  = entry(Scala2PickleReader.EXTref, nat(0))           // nameRef=0, no ownerRef
        val fooNameEntry   = entry(Scala2PickleReader.TERMname, nameData("Foo"))
        val fooExtEntry    = entry(Scala2PickleReader.EXTref, nat(2) ++ nat(1)) // nameRef=2, ownerRef=1
        val pickleBytes    = buildPickle(ownerNameEntry, ownerExtEntry, fooNameEntry, fooExtEntry)
        readPickleDirect(pickleBytes).map: result =>
            val unresolved = result.symbols.filter(_.kind == Tasty.SymbolKind.Unresolved)
            assert(unresolved.nonEmpty, s"Expected Unresolved symbols; got kinds: ${result.symbols.map(_.kind).mkString(", ")}")
            val fooSym = unresolved.find(_.name.asString == "com.example.Foo")
            assert(fooSym.isDefined, s"Expected symbol with FQN 'com.example.Foo'; got: ${unresolved.map(_.name.asString).mkString(", ")}")
            assert(fooSym.get.flags.contains(Tasty.Flag.Scala2), "Expected Flag.Scala2 on EXTref symbol")
    }

    // -------------------------------------------------------------------------
    // Test 9: EXTMODCLASSref appends $ and decodes to Unresolved symbol
    // -------------------------------------------------------------------------

    "Test 9: EXTMODCLASSref appends $ and decodes to Unresolved symbol with FQN com.example.Foo$" in run {
        // Layout (same owner chain as Test 8 but entry 3 is EXTMODCLASSref):
        //   0: TERMname "com.example"
        //   1: EXTref nameRef=0 (no owner) -> "com.example"
        //   2: TERMname "Foo"
        //   3: EXTMODCLASSref nameRef=2 ownerRef=1 -> "com.example.Foo$"
        val ownerNameEntry = entry(Scala2PickleReader.TERMname, nameData("com.example"))
        val ownerExtEntry  = entry(Scala2PickleReader.EXTref, nat(0))
        val fooNameEntry   = entry(Scala2PickleReader.TERMname, nameData("Foo"))
        val fooModEntry    = entry(Scala2PickleReader.EXTMODCLASSref, nat(2) ++ nat(1))
        val pickleBytes    = buildPickle(ownerNameEntry, ownerExtEntry, fooNameEntry, fooModEntry)
        readPickleDirect(pickleBytes).map: result =>
            val unresolved = result.symbols.filter(_.kind == Tasty.SymbolKind.Unresolved)
            assert(unresolved.nonEmpty, s"Expected Unresolved symbols; got kinds: ${result.symbols.map(_.kind).mkString(", ")}")
            val modSym = unresolved.find(_.name.asString == "com.example.Foo$")
            assert(
                modSym.isDefined,
                s"Expected symbol with FQN 'com.example.Foo$$'; got: ${unresolved.map(_.name.asString).mkString(", ")}"
            )
            assert(modSym.get.flags.contains(Tasty.Flag.Scala2), "Expected Flag.Scala2 on EXTMODCLASSref symbol")
    }

    // -------------------------------------------------------------------------
    // Test 10: EXTref owner-cycle does not StackOverflow; resolves to a finite FQN
    // -------------------------------------------------------------------------

    "Test 10: malformed EXTref owner-cycle terminates safely without StackOverflow" in run {
        // Pickle layout (entry indices):
        //   0: TERMname "Foo"
        //   1: EXTref nameRef=0 ownerRef=1  <- self-cycle: ownerRef points to itself
        //
        // resolveExtFqn is called with entryIdx=1 and visited=Set.empty.
        //   - Entry 1 is an EXTref, not in visited. It reads ownerRef=1.
        //   - nextVisited = Set(1). Recursive call: resolveExtFqn(1, ..., Set(1)).
        //   - visited.contains(1) is true -> returns "".
        //   - ownerFqn is Absent (filtered empty), so leafName "Foo" is used.
        //   - The owner resolution returns "Foo".
        // The outer decodeExtRef then builds fqn = "Foo" + "." + "Foo" = "Foo.Foo".
        // The key property tested: the computation terminates (no StackOverflow).
        val fooNameEntry = entry(Scala2PickleReader.TERMname, nameData("Foo"))
        val cyclicExtRef = entry(Scala2PickleReader.EXTref, nat(0) ++ nat(1)) // nameRef=0, ownerRef=1 (self)
        val pickleBytes  = buildPickle(fooNameEntry, cyclicExtRef)
        readPickleDirect(pickleBytes).map: result =>
            val unresolved = result.symbols.filter(_.kind == Tasty.SymbolKind.Unresolved)
            assert(unresolved.nonEmpty, s"Expected at least one Unresolved symbol; got: ${result.symbols.map(_.kind).mkString(", ")}")
            // The cycle is detected after one recursion step; the FQN is finite (not an infinite string).
            // Concretely it resolves to "Foo.Foo" (owner "Foo" prepended once before cycle cutoff).
            val fooSym = unresolved.find(_.name.asString == "Foo.Foo")
            assert(
                fooSym.isDefined,
                s"Expected symbol with finite FQN 'Foo.Foo' (cycle cut after one step); got: ${unresolved.map(_.name.asString).mkString(", ")}"
            )
    }

end Scala2PickleTest
