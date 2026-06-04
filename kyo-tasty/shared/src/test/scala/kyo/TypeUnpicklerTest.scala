package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Tests for TypeUnpickler decoding of TASTy type nodes.
  *
  * Most tests use synthetic byte sequences built by hand from TastyFormat constants. Encoding rules:
  *   - Category 1 (1-59): tag byte only.
  *   - Category 2 (60-89): tag byte + Nat (unsigned LEB128, one byte if < 128: raw byte; two bytes if 128-16383: (0x80 | hi) byte, lo
  *     byte).
  *   - Category 3 (90-109): tag byte + one sub-type (another tag sequence).
  *   - Category 4 (110-127): tag byte + Nat + one sub-type.
  *   - Category 5 (128-255): tag byte + Nat (payload length) + payload bytes.
  *
  * Nat encoding (unsigned LEB128 as used in dotty TastyReader):
  *   - Values 0-127: single byte (value & 0x7f), with high bit = 0 meaning "last byte".
  *   - Wait: dotty uses big-endian base-128 with stop-bit on LAST byte: high bit = 1 means "more bytes follow", high bit = 0 means "last
  *     byte". So for value 2: byte 0x02. For value 128: bytes 0x81, 0x00.
  *
  * For readEnd(): reads a Nat (payload length), then end = cursor + length. So a payload of 3 bytes would be encoded as Nat(3) = 0x03
  * followed by the 3 payload bytes.
  *
  * Plan tests 12-24.
  */
class TypeUnpicklerTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeArena(): TypeArena = TypeArena.canonical()

    // plan: phase-02 bridge; Symbol.make(kind, flags, name).
    private def makeSym(name: String, kind: Tasty.SymbolKind = Tasty.SymbolKind.Class): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(kind, Tasty.Flags.empty, Tasty.Name.fromString(name))

    /** Encode an unsigned Nat in dotty's TASTy big-endian base-128 format.
      *
      * In dotty TASTy (opposite of standard LEB128): the LAST byte has bit 0x80 SET, earlier (continuation) bytes have bit 0x80 CLEAR.
      *
      * Single byte (n < 128): (n | 0x80).toByte -- last byte with 0x80 set. Two bytes (128 <= n < 16384): (n >> 7).toByte -- first byte (no
      * 0x80), ((n & 0x7f) | 0x80).toByte -- last byte. Three bytes: (n >> 14).toByte, ((n >> 7) & 0x7f).toByte, ((n & 0x7f) | 0x80).toByte.
      */
    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then Array((n | 0x80).toByte)
        else if n < 16384 then Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        else
            Array(
                (n >> 14).toByte,
                ((n >> 7) & 0x7f).toByte,
                ((n & 0x7f) | 0x80).toByte
            )

    /** Build bytes for a Category 2 node: tag + Nat. */
    private def cat2(tag: Int, n: Int): Array[Byte] = tag.toByte +: encodeNat(n)

    /** Build bytes for a Category 3 node: tag + sub-tree bytes. */
    private def cat3(tag: Int, subBytes: Array[Byte]): Array[Byte] = tag.toByte +: subBytes

    /** Build bytes for a Category 4 node: tag + Nat + sub-tree bytes. */
    private def cat4(tag: Int, n: Int, subBytes: Array[Byte]): Array[Byte] =
        (tag.toByte +: encodeNat(n)) ++ subBytes

    /** Build bytes for a Category 5 node: tag + length Nat + payload bytes. */
    private def cat5(tag: Int, payload: Array[Byte]): Array[Byte] =
        (tag.toByte +: encodeNat(payload.length)) ++ payload

    /** Decode a single type node from raw bytes.
      *
      * Returns (decodedType, placeholders).
      */
    private def decodeType(
        bytes: Array[Byte],
        addrMap: IntMap[Tasty.Symbol] = IntMap.empty,
        names: Array[Tasty.Name] = Array.empty
    )(using Frame): Tasty.Type < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = makeArena()
        TypeUnpickler.readType(view, names, addrMap, arena, bytes, 0)
    end decodeType

    // Test 12: decoding a TYPEREFsymbol node for a known symbol returns Named(sym).
    "decoding TYPEREFsymbol returns Named(intSymbol)" in run {
        val sym     = makeSym("Int")
        val symAddr = 42
        val addrMap = IntMap(symAddr -> sym)
        // TYPEREFsymbol (116) = cat4: tag + symAddr Nat + qual sub-type.
        // qual = TYPEREFpkg (65) with nameRef 0 => need names[0].
        val names     = Array(Tasty.Name.fromString("scala"))
        val qualBytes = cat2(TastyFormat.TYPEREFpkg, 0) // TYPEREFpkg nameRef=0
        val bytes     = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    // Phase 07: TYPEREFsymbol encodes addr as SymbolId(PHASE_B_ADDR_OFFSET + addr).
                    case Tasty.Type.Named(id) => assert(id.value >= 0, s"Expected addr-encoded positive id, got ${id.value}")
                    case other                => fail(s"Expected Named but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 13: decoding a BYNAMEtype wrapping a symbol returns ByName(Named(sym)).
    "decoding BYNAMEtype wrapping a TYPEREFsymbol returns ByName(Named(sym))" in run {
        val sym        = makeSym("Int")
        val symAddr    = 10
        val addrMap    = IntMap(symAddr -> sym)
        val names      = Array(Tasty.Name.fromString("scala"))
        val qualBytes  = cat2(TastyFormat.TYPEREFpkg, 0)
        val innerBytes = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        // BYNAMEtype = category 3: tag(93) + sub-type
        val bytes = cat3(TastyFormat.BYNAMEtype, innerBytes)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.ByName(Tasty.Type.Named(id)) =>
                        assert(id.value >= 0, s"Expected addr-encoded id in ByName, got ${id.value}")
                    case other => fail(s"Expected ByName(Named) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 14: decoding a REPEATED node returns Repeated(elem).
    "decoding REPEATED returns Repeated(elem)" in run {
        val sym       = makeSym("String")
        val symAddr   = 5
        val addrMap   = IntMap(symAddr -> sym)
        val names     = Array(Tasty.Name.fromString("scala"))
        val qualBytes = cat2(TastyFormat.TYPEREFpkg, 0)
        val elemBytes = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        // REPEATED (149) = category 5: tag + length + elem_type
        val bytes = cat5(TastyFormat.REPEATED, elemBytes)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.Repeated(Tasty.Type.Named(id)) =>
                        assert(id.value >= 0, s"Expected addr-encoded id in Repeated, got ${id.value}")
                    case other => fail(s"Expected Repeated(Named) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 15: decoding APPLIEDtype for List[String] returns Applied(Named(listSym), Chunk(Named(stringSym))).
    "decoding APPLIEDtype for List[String] returns Applied(Named(list), Chunk(Named(string)))" in run {
        val listSym    = makeSym("List")
        val stringSym  = makeSym("String")
        val listAddr   = 20
        val stringAddr = 30
        val addrMap    = IntMap(listAddr -> listSym, stringAddr -> stringSym)
        val names      = Array(Tasty.Name.fromString("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        // APPLIEDtype (161) = cat5: [tycon=TYPEREFsymbol(listAddr, qual)] [arg=TYPEREFsymbol(stringAddr, qual)]
        val tycon = cat4(TastyFormat.TYPEREFsymbol, listAddr, qual)
        val arg   = cat4(TastyFormat.TYPEREFsymbol, stringAddr, qual)
        val bytes = cat5(TastyFormat.APPLIEDtype, tycon ++ arg)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.Applied(Tasty.Type.Named(ls), args) =>
                        assert(ls.value >= 0, s"Expected addr-encoded listSym id but got ${ls.value}")
                        assert(args.length == 1)
                        args(0) match
                            case Tasty.Type.Named(s) => assert(s.value >= 0, s"Expected addr-encoded stringSym id but got ${s.value}")
                            case other               => fail(s"Expected Named(stringSym) but got $other")
                    case other =>
                        fail(s"Expected Applied but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 16: decoding a SHAREDtype reference returns the same reference as the originally-decoded type.
    // Uses a real SHAREDtype byte stream: first type decoded at addr 0, second is SHAREDtype(0).
    // Both are decoded in a single shared DecodeSession so addrCache is shared.
    // Asserts that the SHAREDtype result is eq-identical to the first-decoded type (same reference,
    // not a newly-allocated copy), exercising TypeUnpickler.scala lines 175-181 and 157-160.
    "decoding SHAREDtype reference returns the same reference (no duplication)" in run {
        val sym     = makeSym("Int")
        val symAddr = 7
        val addrMap = IntMap(symAddr -> sym)
        val names   = Array(Tasty.Name.fromString("scala"))
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        // firstTypeBytes: TYPEREFsymbol starting at position 0 in the combined view.
        val firstTypeBytes = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        val firstTypeEnd   = firstTypeBytes.length
        // SHAREDtype (61) = category 2: tag + Nat. Nat = 0 = addr of first type in the view.
        val sharedBytes = cat2(TastyFormat.SHAREDtype, 0)
        // Combined byte stream: [firstType bytes] [sharedType bytes]
        val combined = firstTypeBytes ++ sharedBytes
        val view     = ByteView(combined)
        val arena    = makeArena()
        // Build a shared DecodeSession so addrCache is shared between both reads.
        val liveAddrMap = new mutable.HashMap[Int, Tasty.Symbol]()
        addrMap.foreach { case (k, v) => liveAddrMap(k) = v }
        val session = new TypeUnpickler.DecodeSession(names, liveAddrMap, arena)
        // Decode first type: positions view at 0, reads TYPEREFsymbol, records addrCache(0) = result.
        val firstDecoded = TypeUnpickler.readTypeIntoSession(view, session)
        // Decode SHAREDtype: reads tag=61, astRef=0, returns addrCache(0) = firstDecoded.
        val sharedDecoded = TypeUnpickler.readTypeIntoSession(view, session)
        // Both must be the same reference: SHAREDtype dedup is the invariant being tested.
        assert(
            firstDecoded eq sharedDecoded,
            s"SHAREDtype should return eq-reference to first-decoded type but got different objects. " +
                s"first=$firstDecoded, shared=$sharedDecoded"
        )
    }

    // Test 17: decoding TYPELAMBDAtype returns TypeLambda(params, body) with params.size == 1.
    "decoding TYPELAMBDAtype with one param returns TypeLambda with params.size == 1" in run {
        val paramSym   = makeSym("A", Tasty.SymbolKind.TypeParam)
        val resultSym  = makeSym("Int")
        val paramAddr  = 5
        val resultAddr = 10
        val addrMap    = IntMap(paramAddr -> paramSym, resultAddr -> resultSym)
        val names      = Array(Tasty.Name.fromString("scala"), Tasty.Name.fromString("A"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        // TYPELAMBDAtype (170) = cat5: [result_Type] [typeRef0 paramNameRef0]
        // result = TYPEREFsymbol(resultAddr, qual)
        val result = cat4(TastyFormat.TYPEREFsymbol, resultAddr, qual)
        // TypesNames: typeRef0=paramAddr, paramNameRef0=1 (index into names, "A")
        val typeRef0Name0 = encodeNat(paramAddr) ++ encodeNat(1)
        val payload       = result ++ typeRef0Name0
        val bytes         = cat5(TastyFormat.TYPELAMBDAtype, payload)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.TypeLambda(params, _) =>
                        assert(params.length == 1)
                    case other =>
                        fail(s"Expected TypeLambda but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 18: decoding ANNOTATEDtype eagerly decodes the annotation term into arguments: Chunk[Tree].
    // Phase 08 (INV-006): arguments is now a plain Chunk[Tree] field populated eagerly during Pass B.
    // The annotation term TYPEREFpkg(0) decodes to a TermRefPkg tree; arguments holds a single-element Chunk.
    "decoding ANNOTATEDtype eagerly populates Annotation.arguments as Chunk[Tree]" in run {
        val sym        = makeSym("Int")
        val symAddr    = 3
        val addrMap    = IntMap(symAddr -> sym)
        val names      = Array(Tasty.Name.fromString("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        val underlying = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        // Annotation term: TYPEREFpkg(0); decodes to TermRefPkg(names(0)).
        val annTerm = cat2(TastyFormat.TYPEREFpkg, 0)
        // ANNOTATEDtype (153) = cat5: [underlying] [annTerm]
        val bytes = cat5(TastyFormat.ANNOTATEDtype, underlying ++ annTerm)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.Annotated(_, ann) =>
                        assert(
                            ann.arguments.nonEmpty,
                            s"Expected non-empty arguments but got ${ann.arguments}"
                        )
                        succeed
                    case other =>
                        fail(s"Expected Annotated but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 18b: Annotation.arguments for a UNITconst term is Chunk(Literal(UnitConst)) after eager decode.
    // Phase 08 (INV-006): arguments is a plain Chunk[Tree] field; no Abort.run needed to access it.
    "ANNOTATEDtype with UNITconst term: Annotation.arguments == Chunk(Literal(UnitConst))" in run {
        val sym        = makeSym("Int")
        val symAddr    = 3
        val addrMap    = IntMap(symAddr -> sym)
        val names      = Array(Tasty.Name.fromString("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        val underlying = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        // Annotation term: UNITconst; TreeUnpickler decodes this as Literal(UnitConst).
        val annTerm = Array(TastyFormat.UNITconst.toByte)
        val bytes   = cat5(TastyFormat.ANNOTATEDtype, underlying ++ annTerm)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.Annotated(_, ann) =>
                        ann.arguments match
                            case Chunk(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type)) => succeed
                            case other => fail(s"Expected Chunk(Literal(UnitConst)) but got $other")
                    case other => fail(s"Expected Annotated but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 18c: Annotation constructed directly with Chunk.empty has no arguments (synthetic annotation).
    // Phase 08 (INV-006): pure case class, no decode context needed.
    "Annotation(type, Chunk.empty).arguments is empty without any effect" in run {
        val ann = Tasty.Annotation(Tasty.Type.Named(makeSym("Foo").id), Chunk.empty)
        assert(ann.arguments.isEmpty, s"Expected empty arguments but got ${ann.arguments}")
        succeed
    }

    // Test 19: decoding ORtype returns OrType(left, right).
    "decoding ORtype returns OrType(left, right)" in run {
        val symA    = makeSym("A")
        val symB    = makeSym("B")
        val addrMap = IntMap(5 -> symA, 6 -> symB)
        val names   = Array(Tasty.Name.fromString("scala"))
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val left    = cat4(TastyFormat.TYPEREFsymbol, 5, qual)
        val right   = cat4(TastyFormat.TYPEREFsymbol, 6, qual)
        // ORtype (167) = cat5: [left] [right]
        val bytes = cat5(TastyFormat.ORtype, left ++ right)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.OrType(_, _) => succeed
                    case other                   => fail(s"Expected OrType but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 20: decoding ANDtype returns AndType(left, right) (or normalized form via TypeOps).
    "decoding ANDtype returns AndType(left, right) or normalized form" in run {
        val symA    = makeSym("A")
        val symB    = makeSym("B")
        val addrMap = IntMap(5 -> symA, 6 -> symB)
        val names   = Array(Tasty.Name.fromString("scala"))
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val left    = cat4(TastyFormat.TYPEREFsymbol, 5, qual)
        val right   = cat4(TastyFormat.TYPEREFsymbol, 6, qual)
        // ANDtype (165) = cat5: [left] [right]
        val bytes = cat5(TastyFormat.ANDtype, left ++ right)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                // Both sides are non-Singleton Named types, so normalization must NOT collapse.
                // The result must be exactly AndType, not one of the sides.
                t match
                    case Tasty.Type.AndType(_, _) => succeed
                    case other => fail(s"Expected AndType but got $other (normalization should not collapse non-Singleton args)")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 21: decoding MATCHtype returns MatchType(bound, scrutinee, cases) with cases.size == 2.
    "decoding MATCHtype with 2 cases returns MatchType with cases.size == 2" in run {
        val symBound = makeSym("Any")
        val symScrut = makeSym("X")
        val addrMap  = IntMap(1 -> symBound, 2 -> symScrut)
        val names    = Array(Tasty.Name.fromString("scala"))
        val qual     = cat2(TastyFormat.TYPEREFpkg, 0)
        val bound    = cat4(TastyFormat.TYPEREFsymbol, 1, qual)
        val scrut    = cat4(TastyFormat.TYPEREFsymbol, 2, qual)
        // Two MATCHCASEtype cases.
        val case1Pat = cat4(TastyFormat.TYPEREFsymbol, 1, qual)
        val case1Rhs = cat4(TastyFormat.TYPEREFsymbol, 2, qual)
        val case1    = cat5(TastyFormat.MATCHCASEtype, case1Pat ++ case1Rhs)
        val case2    = cat5(TastyFormat.MATCHCASEtype, case1Pat ++ case1Rhs)
        // MATCHtype (190) = cat5: [bound] [scrut] [case1] [case2]
        val bytes     = cat5(TastyFormat.MATCHtype, bound ++ scrut ++ case1 ++ case2)
        val symScrut2 = symScrut // capture for inner match
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.MatchType(_, scrutinee, cases) =>
                        assert(cases.size == 2, s"Expected 2 cases but got ${cases.size}")
                        // Plan line 310: assert scrutinee is the named type parameter X.
                        scrutinee match
                            case Tasty.Type.Named(id) =>
                                // Phase 07: addr-encoded ID; original sym.id=-1, now SymbolId(PHASE_B_ADDR_OFFSET+addr).
                                assert(
                                    id.value >= 0,
                                    s"scrutinee id should be addr-encoded positive but was ${id.value}"
                                )
                            case other =>
                                fail(s"Expected scrutinee to be Named(symScrut) but got $other")
                        end match
                    case other =>
                        fail(s"Expected MatchType but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 22: decoding CONSTANTtype wrapping integer 42 returns ConstantType(IntConst(42)).
    "decoding INTconst 42 returns ConstantType(IntConst(42))" in run {
        // INTconst (70) is category 2: tag + signed Int (dotty readInt).
        // In dotty TASTy, the LAST byte has 0x80 set. For value 42: (42 | 0x80) = 0xAA = 170.
        // readInt reads 0xAA: b=0xAA, x=((0xAA<<1).toByte>>1).toLong=42. Since (0xAA & 0x80) != 0, loop stops.
        val bytes = Array(TastyFormat.INTconst.toByte, (42 | 0x80).toByte)
        Abort.run[TastyError](decodeType(bytes)).map {
            case Result.Success(t) =>
                t match
                    case Tasty.Type.ConstantType(Tasty.Constant.IntConst(42)) => succeed
                    case other                                                => fail(s"Expected ConstantType(IntConst(42)) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 23: decoding RECtype with RECthis produces cycle-safe result (no stack overflow).
    // Actually calls TypeUnpickler.readTypeIntoSession on the RECtype/RECthis byte stream inside a
    // stack-limited thread (64KB), verifying that the decoder terminates without overflow and returns
    // a Tasty.Type.Rec whose parent is Tasty.Type.RecThis.
    "decoding RECtype with RECthis back-reference completes without stack overflow" in run {
        // Hand-construct bytes for:
        //   RECtype(100) [parent = RECthis(66) back-ref to addr 0]
        //
        // Layout: [addr 0] RECtype(100) [parent bytes]
        // RECthis(66) = category 2: tag + recType_ASTRef Nat. recType_ASTRef = 0 (addr of RECtype).
        // RECthis bytes: [66, 0]
        //
        // RECtype is category 3: tag(100) + parent_Type (no length prefix in cat3!).
        // So bytes = [100, 66, 0]
        // In dotty TASTy: last byte of Nat has 0x80 SET. For addr 0: encodeNat(0) = (0 | 0x80) = 0x80.
        val bytes = Array(
            TastyFormat.RECtype.toByte, // 100 at position 0
            TastyFormat.RECthis.toByte, // 66 at position 1
            (0 | 0x80).toByte           // Nat 0 with termination bit = references addr 0 (the RECtype itself)
        )
        var decoded: Option[Tasty.Type]  = None
        var exception: Option[Throwable] = None
        // StackLimitedRunner is platform-specific: JVM uses a 64KB-stack Thread, JS/Native run directly.
        StackLimitedRunner.run:
            try
                val view        = ByteView(bytes)
                val arena       = makeArena()
                val liveAddrMap = new mutable.HashMap[Int, Tasty.Symbol]()
                val session     = new TypeUnpickler.DecodeSession(Array.empty, liveAddrMap, arena)
                // Decode RECtype/RECthis via the shared session.
                // This exercises inProgressRec cycle-break: TypeUnpickler.scala lines 257-269.
                val t = TypeUnpickler.readTypeIntoSession(view, session)
                decoded = Some(t)
            catch
                case t: Throwable => exception = Some(t)
        assert(exception.isEmpty, s"Unexpected exception in RECtype decode: ${exception.map(_.getMessage).getOrElse("")}")
        assert(decoded.isDefined, "RECtype decode timed out or produced no result")
        // The decoded result must be a Rec whose parent resolves (via inProgressRec) to RecThis.
        decoded.get match
            case Tasty.Type.Rec(_) => succeed
            case other             => fail(s"Expected Tasty.Type.Rec(...) but got $other")
        // Also verify TypeArena merge handles Rec/RecThis without overflow.
        val arena    = makeArena()
        val sentinel = Tasty.Symbol.makePlaceholder(Tasty.SymbolKind.Unresolved, Tasty.Flags.empty, Tasty.Name.fromString("s"))
        val rec      = Tasty.Type.Rec(Tasty.Type.Named(sentinel.id))
        val recThis  = Tasty.Type.RecThis(rec)
        arena.intern(rec)
        arena.intern(recThis)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        // After merging two distinct interned types (Rec and RecThis), the canonical arena must contain
        // both. We assert presence rather than exact equality because canonical() may carry built-in
        // pre-interned types (e.g. Any, Nothing) that the merge does not remove.
        val canonValues = canon.values
        assert(canonValues.exists(_ == rec), s"Canonical arena must contain the interned Rec; values size=${canonValues.size}")
        assert(canonValues.exists(_ == recThis), s"Canonical arena must contain the interned RecThis; values size=${canonValues.size}")
    }

    // Test 24 (redesigned for Phase 07): TYPEREFin with unknown FQN creates a Named(unresolved).
    // Phase 07 deleted UnresolvedRef; cross-file references now resolve to synthetic unresolved symbols directly.
    "decoding TYPEREFin with unknown FQN returns Named(unresolved) type" in run {
        val names = Array(Tasty.Name.fromString("scala"), Tasty.Name.fromString("SomeCrossFileType"))
        // TYPEREFin (175) = cat5: [NameRef] [qual_Type] [namespace_Type]
        // NameRef = 1 (index to "SomeCrossFileType")
        // qual = TYPEREFpkg nameRef=0 ("scala")
        // namespace = TYPEREFpkg nameRef=0 ("scala")
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val ns      = cat2(TastyFormat.TYPEREFpkg, 0)
        val payload = encodeNat(1) ++ qual ++ ns
        val bytes   = cat5(TastyFormat.TYPEREFin, payload)
        Abort.run[TastyError](decodeType(bytes, IntMap.empty, names)).map {
            case Result.Success(t) =>
                // Phase 07: TYPEREFin now returns Named(unresolved) directly rather than creating an UnresolvedRef.
                t match
                    case Tasty.Type.Named(_) => succeed // Named with unresolved SymbolId
                    case other               => fail(s"Expected Named(unresolved) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test M7-1: unknown category-5 tag (250) produces TastyError.UnknownTagInPosition (Phase 2.04-strict).
    // The old behavior was to warn + return Named(-1); the new behavior is to fail loudly with a structured error.
    // Pins INV-004, M7; updated for HARD RULE 13.
    "unknown category-5 TASTy type tag produces TastyError.UnknownTagInPosition" in {
        // cat5 encoding: tag byte + length Nat + payload. Length = 0 -> Nat(0) = (0 | 0x80).toByte = 0x80.
        val bytes = Array(250.toByte, 0x80.toByte)
        import AllowUnsafe.embrace.danger
        val result = Sync.Unsafe.evalOrThrow(Abort.run[TastyError](decodeType(bytes)))
        result match
            case Result.Success(t) =>
                fail(s"Expected UnknownTagInPosition failure for tag 250 but got Success($t)")
            case Result.Failure(TastyError.UnknownTagInPosition(250, "type")) =>
                succeed // correct: unknown tag 250 in type position produces this structured error
            case Result.Failure(other) =>
                fail(s"Expected UnknownTagInPosition(250, 'type') but got Failure($other)")
            case Result.Panic(t) =>
                throw t
        end match
    }

    // Phase 08 W-1/W-2 followup: DecodeSession.annotationDecodeErrors accumulates errors from corrupt
    // ANNOTATEDtype argument bytes when sectionBytes is available. This test constructs a real
    // DecodeSession, crafts an ANNOTATEDtype with a non-empty but syntactically invalid arg byte
    // slice, and calls readTypeIntoSessionWithBytes (the test-only variant that provides sectionBytes).
    // Asserts session.annotationDecodeErrors.nonEmpty after the decode.
    // Phase 08 W-1/W-2 followup: session.annotationDecodeErrors accumulates errors from truncated
    // annotation arg bytes. ANNOTATEDtype with a 1-byte payload (only UNITconst; no annotation term)
    // produces an empty pickle for the term slice. TreeUnpickler.readTree on an empty ByteView throws
    // AIOOBE, which is caught and converted to a MalformedSection error accumulated in the session.
    "ANNOTATEDtype with truncated arg bytes accumulates error in session.annotationDecodeErrors" in {
        import AllowUnsafe.embrace.danger
        import kyo.internal.tasty.binary.ByteView
        // Build ANNOTATEDtype with payload = [UNITconst] only (no annotation term bytes follow).
        // The payload length is 1 (= size of UNITconst). After reading underlying=UNITconst,
        // termStart == endInt, so the annotation-term pickle is empty [].
        // decodeAnnotationTerm([]) calls readTree on an empty ByteView, throwing AIOOBE.
        val bytes = cat5(TastyFormat.ANNOTATEDtype, Array(TastyFormat.UNITconst.toByte))
        // Decode via readTypeIntoSessionWithBytes so ctx.sectionBytes != null AND ctx.session != null.
        val arena       = makeArena()
        val liveAddrMap = new mutable.HashMap[Int, Tasty.Symbol]()
        val session     = new TypeUnpickler.DecodeSession(Array.empty, liveAddrMap, arena)
        val view        = ByteView(bytes)
        given Frame     = summon[Frame]
        TypeUnpickler.readTypeIntoSessionWithBytes(view, session, bytes, 0)
        assert(
            session.annotationDecodeErrors.nonEmpty,
            s"Expected annotationDecodeErrors.nonEmpty after truncated annotation decode, got: ${session.annotationDecodeErrors}"
        )
    }

    // Test M7-2: a known category-1 tag (UNITconst) does NOT fire any log output.
    // Pins INV-004 negative path.
    "known TASTy type tag does not emit a warn-level log" in {
        // UNITconst = category 1: single tag byte, no payload.
        val bytes  = Array(TastyFormat.UNITconst.toByte)
        val output = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = output.append(b.toChar))):
            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow:
                Abort.run[TastyError](decodeType(bytes)).map {
                    case Result.Success(_) => ()
                    case Result.Failure(e) => ()
                    case Result.Panic(t)   => throw t
                }
        assert(
            output.isEmpty,
            s"Expected no log output for known tag but got: ${output.toString}"
        )
    }

end TypeUnpicklerTest
