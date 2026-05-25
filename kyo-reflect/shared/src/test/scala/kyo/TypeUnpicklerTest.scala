package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.UnresolvedRef
import kyo.internal.reflect.symbol.SingleAssign
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TypeUnpickler
import kyo.internal.reflect.type_.TypeArena
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

    private def makeArena(): TypeArena   = TypeArena.canonical()
    private def makeHome(): ClasspathRef = new ClasspathRef

    private def makeSym(name: String, kind: Reflect.SymbolKind = Reflect.SymbolKind.Class): Reflect.Symbol =
        Reflect.Symbol.make(
            kind,
            Reflect.Flags.empty,
            Reflect.Name(name),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0),
            Absent
        )

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
        addrMap: Map[Int, Reflect.Symbol] = Map.empty,
        names: Array[Reflect.Name] = Array.empty
    )(using Frame): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError]) =
        val view  = ByteView(bytes)
        val arena = makeArena()
        val home  = makeHome()
        TypeUnpickler.readType(view, names, addrMap, arena, home)
    end decodeType

    // Test 12: decoding a TYPEREFsymbol node for a known symbol returns Named(sym).
    "decoding TYPEREFsymbol returns Named(intSymbol)" in run {
        val sym     = makeSym("Int")
        val symAddr = 42
        val addrMap = Map(symAddr -> sym)
        // TYPEREFsymbol (116) = cat4: tag + symAddr Nat + qual sub-type.
        // qual = TYPEREFpkg (65) with nameRef 0 => need names[0].
        val names     = Array(Reflect.Name("scala"))
        val qualBytes = cat2(TastyFormat.TYPEREFpkg, 0) // TYPEREFpkg nameRef=0
        val bytes     = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.Named(s) => assert(s eq sym)
                    case other                 => fail(s"Expected Named but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 13: decoding a BYNAMEtype wrapping a symbol returns ByName(Named(sym)).
    "decoding BYNAMEtype wrapping a TYPEREFsymbol returns ByName(Named(sym))" in run {
        val sym        = makeSym("Int")
        val symAddr    = 10
        val addrMap    = Map(symAddr -> sym)
        val names      = Array(Reflect.Name("scala"))
        val qualBytes  = cat2(TastyFormat.TYPEREFpkg, 0)
        val innerBytes = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        // BYNAMEtype = category 3: tag(93) + sub-type
        val bytes = cat3(TastyFormat.BYNAMEtype, innerBytes)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.ByName(Reflect.Type.Named(s)) => assert(s eq sym)
                    case other                                      => fail(s"Expected ByName(Named) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 14: decoding a REPEATED node returns Repeated(elem).
    "decoding REPEATED returns Repeated(elem)" in run {
        val sym       = makeSym("String")
        val symAddr   = 5
        val addrMap   = Map(symAddr -> sym)
        val names     = Array(Reflect.Name("scala"))
        val qualBytes = cat2(TastyFormat.TYPEREFpkg, 0)
        val elemBytes = cat4(TastyFormat.TYPEREFsymbol, symAddr, qualBytes)
        // REPEATED (149) = category 5: tag + length + elem_type
        val bytes = cat5(TastyFormat.REPEATED, elemBytes)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.Repeated(Reflect.Type.Named(s)) => assert(s eq sym)
                    case other                                        => fail(s"Expected Repeated(Named) but got $other")
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
        val addrMap    = Map(listAddr -> listSym, stringAddr -> stringSym)
        val names      = Array(Reflect.Name("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        // APPLIEDtype (161) = cat5: [tycon=TYPEREFsymbol(listAddr, qual)] [arg=TYPEREFsymbol(stringAddr, qual)]
        val tycon = cat4(TastyFormat.TYPEREFsymbol, listAddr, qual)
        val arg   = cat4(TastyFormat.TYPEREFsymbol, stringAddr, qual)
        val bytes = cat5(TastyFormat.APPLIEDtype, tycon ++ arg)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.Applied(Reflect.Type.Named(ls), args) =>
                        assert(ls eq listSym)
                        assert(args.length == 1)
                        args(0) match
                            case Reflect.Type.Named(s) => assert(s eq stringSym)
                            case other                 => fail(s"Expected Named(stringSym) but got $other")
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
        val addrMap = Map(symAddr -> sym)
        val names   = Array(Reflect.Name("scala"))
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
        val home     = makeHome()
        // Build a shared DecodeSession so addrCache is shared between both reads.
        val liveAddrMap = new mutable.HashMap[Int, Reflect.Symbol]()
        addrMap.foreach { case (k, v) => liveAddrMap(k) = v }
        val session = new TypeUnpickler.DecodeSession(names, liveAddrMap, arena, home)
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
        val paramSym   = makeSym("A", Reflect.SymbolKind.TypeParam)
        val resultSym  = makeSym("Int")
        val paramAddr  = 5
        val resultAddr = 10
        val addrMap    = Map(paramAddr -> paramSym, resultAddr -> resultSym)
        val names      = Array(Reflect.Name("scala"), Reflect.Name("A"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        // TYPELAMBDAtype (170) = cat5: [result_Type] [typeRef0 paramNameRef0]
        // result = TYPEREFsymbol(resultAddr, qual)
        val result = cat4(TastyFormat.TYPEREFsymbol, resultAddr, qual)
        // TypesNames: typeRef0=paramAddr, paramNameRef0=1 (index into names, "A")
        val typeRef0Name0 = encodeNat(paramAddr) ++ encodeNat(1)
        val payload       = result ++ typeRef0Name0
        val bytes         = cat5(TastyFormat.TYPELAMBDAtype, payload)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.TypeLambda(params, _) =>
                        assert(params.length == 1)
                    case other =>
                        fail(s"Expected TypeLambda but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 18: decoding ANNOTATEDtype returns Annotated(underlying, annotation).
    "decoding ANNOTATEDtype returns Annotated(underlying, annotation)" in run {
        val sym        = makeSym("Int")
        val symAddr    = 3
        val addrMap    = Map(symAddr -> sym)
        val names      = Array(Reflect.Name("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        val underlying = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        // Annotation term: use a TYPEREFpkg(0) as a minimal annotation placeholder.
        val annTerm = cat2(TastyFormat.TYPEREFpkg, 0)
        // ANNOTATEDtype (153) = cat5: [underlying] [annTerm]
        val bytes = cat5(TastyFormat.ANNOTATEDtype, underlying ++ annTerm)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.Annotated(_, _) => succeed
                    case other                        => fail(s"Expected Annotated but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 19: decoding ORtype returns OrType(left, right).
    "decoding ORtype returns OrType(left, right)" in run {
        val symA    = makeSym("A")
        val symB    = makeSym("B")
        val addrMap = Map(5 -> symA, 6 -> symB)
        val names   = Array(Reflect.Name("scala"))
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val left    = cat4(TastyFormat.TYPEREFsymbol, 5, qual)
        val right   = cat4(TastyFormat.TYPEREFsymbol, 6, qual)
        // ORtype (167) = cat5: [left] [right]
        val bytes = cat5(TastyFormat.ORtype, left ++ right)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.OrType(_, _) => succeed
                    case other                     => fail(s"Expected OrType but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 20: decoding ANDtype returns AndType(left, right) (or normalized form via TypeOps).
    "decoding ANDtype returns AndType(left, right) or normalized form" in run {
        val symA    = makeSym("A")
        val symB    = makeSym("B")
        val addrMap = Map(5 -> symA, 6 -> symB)
        val names   = Array(Reflect.Name("scala"))
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val left    = cat4(TastyFormat.TYPEREFsymbol, 5, qual)
        val right   = cat4(TastyFormat.TYPEREFsymbol, 6, qual)
        // ANDtype (165) = cat5: [left] [right]
        val bytes = cat5(TastyFormat.ANDtype, left ++ right)
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                // Both sides are non-Singleton Named types, so normalization must NOT collapse.
                // The result must be exactly AndType, not one of the sides.
                t match
                    case Reflect.Type.AndType(_, _) => succeed
                    case other => fail(s"Expected AndType but got $other (normalization should not collapse non-Singleton args)")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 21: decoding MATCHtype returns MatchType(bound, scrutinee, cases) with cases.size == 2.
    "decoding MATCHtype with 2 cases returns MatchType with cases.size == 2" in run {
        val symBound = makeSym("Any")
        val symScrut = makeSym("X")
        val addrMap  = Map(1 -> symBound, 2 -> symScrut)
        val names    = Array(Reflect.Name("scala"))
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
        Abort.run[ReflectError](decodeType(bytes, addrMap, names)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.MatchType(_, scrutinee, cases) =>
                        assert(cases.size == 2, s"Expected 2 cases but got ${cases.size}")
                        // Plan line 310: assert scrutinee is the named type parameter X.
                        scrutinee match
                            case Reflect.Type.Named(sym) =>
                                assert(
                                    sym eq symScrut2,
                                    s"scrutinee symbol expected symScrut (X) but was ${sym.name.asString}"
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
        Abort.run[ReflectError](decodeType(bytes)).map {
            case Result.Success((t, _)) =>
                t match
                    case Reflect.Type.ConstantType(Reflect.Constant.IntConst(42)) => succeed
                    case other => fail(s"Expected ConstantType(IntConst(42)) but got $other")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Test 23: decoding RECtype with RECthis produces cycle-safe result (no stack overflow).
    // Actually calls TypeUnpickler.readTypeIntoSession on the RECtype/RECthis byte stream inside a
    // stack-limited thread (64KB), verifying that the decoder terminates without overflow and returns
    // a Reflect.Type.Rec whose parent is Reflect.Type.RecThis.
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
        var decoded: Option[Reflect.Type] = None
        var exception: Option[Throwable]  = None
        // StackLimitedRunner is platform-specific: JVM uses a 64KB-stack Thread, JS/Native run directly.
        StackLimitedRunner.run:
            try
                val view        = ByteView(bytes)
                val arena       = makeArena()
                val home        = makeHome()
                val liveAddrMap = new mutable.HashMap[Int, Reflect.Symbol]()
                val session     = new TypeUnpickler.DecodeSession(Array.empty, liveAddrMap, arena, home)
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
            case Reflect.Type.Rec(_) => succeed
            case other               => fail(s"Expected Reflect.Type.Rec(...) but got $other")
        // Also verify TypeArena merge handles Rec/RecThis without overflow.
        val arena = makeArena()
        val sentinel = Reflect.Symbol.make(
            Reflect.SymbolKind.Unresolved,
            Reflect.Flags.empty,
            Reflect.Name("s"),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0),
            Absent
        )
        val rec     = Reflect.Type.Rec(Reflect.Type.Named(sentinel))
        val recThis = Reflect.Type.RecThis(rec)
        arena.intern(rec)
        arena.intern(recThis)
        val canon = TypeArena.canonical()
        arena.merge(canon)
        assert(canon.values.nonEmpty)
    }

    // Test 24: a cross-file TYPEREFin whose FQN is not in addrMap produces an UnresolvedRef placeholder.
    "decoding TYPEREFin with unknown FQN produces UnresolvedRef placeholder" in run {
        val names = Array(Reflect.Name("scala"), Reflect.Name("SomeCrossFileType"))
        // TYPEREFin (175) = cat5: [NameRef] [qual_Type] [namespace_Type]
        // NameRef = 1 (index to "SomeCrossFileType")
        // qual = TYPEREFpkg nameRef=0 ("scala")
        // namespace = TYPEREFpkg nameRef=0 ("scala")
        val qual    = cat2(TastyFormat.TYPEREFpkg, 0)
        val ns      = cat2(TastyFormat.TYPEREFpkg, 0)
        val payload = encodeNat(1) ++ qual ++ ns
        val bytes   = cat5(TastyFormat.TYPEREFin, payload)
        Abort.run[ReflectError](decodeType(bytes, Map.empty, names)).map {
            case Result.Success((t, placeholders)) =>
                // The FQN was not in addrMap, so we should get an UnresolvedRef.
                assert(placeholders.nonEmpty, s"Expected non-empty placeholders but got empty. Type: $t")
                val fqns = placeholders.map(_.fqn).toList
                assert(fqns.contains("SomeCrossFileType"), s"Expected 'SomeCrossFileType' in $fqns")
            case Result.Failure(e) => fail(s"Expected success but got $e")
            case Result.Panic(t)   => throw t
        }
    }

end TypeUnpicklerTest
