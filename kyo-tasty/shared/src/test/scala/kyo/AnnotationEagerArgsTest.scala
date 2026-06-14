package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap

/** Pinning tests for Annotation.arguments: eagerly decoded at open time, decode failures
  * accumulate as empty Chunk, and the case-class has no legacy fields.
  */
class AnnotationEagerArgsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeSym(name: String): LoadingSymbol.Materialising =
        LoadingSymbol.Materialising(id = 0, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name(name))

    private def encodeNat(n: Int): Array[Byte] =
        if n < 128 then Array((n | 0x80).toByte)
        else if n < 16384 then Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        else Array((n >> 14).toByte, ((n >> 7) & 0x7f).toByte, ((n & 0x7f) | 0x80).toByte)

    private def cat2(tag: Int, n: Int): Array[Byte] = tag.toByte +: encodeNat(n)
    private def cat4(tag: Int, n: Int, subBytes: Array[Byte]): Array[Byte] =
        (tag.toByte +: encodeNat(n)) ++ subBytes
    private def cat5(tag: Int, payload: Array[Byte]): Array[Byte] =
        (tag.toByte +: encodeNat(payload.length)) ++ payload

    private def decodeType(
        bytes: Array[Byte],
        addrMap: IntMap[LoadingSymbol.Materialising] = IntMap.empty,
        names: Array[Tasty.Name] = Array.empty
    )(using Frame): Tasty.Type < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = TypeArena.canonical()
        Sync.Unsafe.defer(Abort.get(TypeUnpickler.readType(view, names, addrMap, arena, bytes, 0)))
    end decodeType

    "Annotation.arguments populated at open time as plain Chunk[Tree]" in {
        val symbol     = makeSym("Int")
        val symAddr    = 3
        val addrMap    = IntMap(symAddr -> symbol)
        val names      = Array(Tasty.Name("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        val underlying = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        // UNITconst is category 1 (single byte); decodes to Literal(UnitConst).
        val annTerm = Array(TastyFormat.UNITconst.toByte)
        val bytes   = cat5(TastyFormat.ANNOTATEDtype, underlying ++ annTerm)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(Tasty.Type.Annotated(_, annotation)) =>
                // arguments is a plain field; access requires no effect row at all.
                annotation.arguments match
                    case Chunk(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type)) => succeed
                    case other => fail(s"Expected Chunk(Literal(UnitConst)) but got $other")
            case Result.Success(other) =>
                fail(s"Expected Annotated type but got $other")
            case Result.Failure(e) =>
                fail(s"Unexpected decode failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "decode failure produces an empty arguments chunk" in {
        val symbol     = makeSym("Int")
        val symAddr    = 3
        val addrMap    = IntMap(symAddr -> symbol)
        val names      = Array(Tasty.Name("scala"))
        val qual       = cat2(TastyFormat.TYPEREFpkg, 0)
        val underlying = cat4(TastyFormat.TYPEREFsymbol, symAddr, qual)
        // Tag 50 is below firstASTtag (60) and not a valid modifier; TreeUnpickler throws DecodeException.
        val corruptTerm = Array(50.toByte)
        val bytes       = cat5(TastyFormat.ANNOTATEDtype, underlying ++ corruptTerm)
        Abort.run[TastyError](decodeType(bytes, addrMap, names)).map {
            case Result.Success(Tasty.Type.Annotated(_, annotation)) =>
                // decode error produces arguments = Chunk.empty, not a Kyo failure.
                assert(annotation.arguments.isEmpty, s"Expected empty arguments but got ${annotation.arguments}")
            case Result.Success(other) =>
                fail(s"Expected Annotated type but got $other")
            case Result.Failure(e) =>
                fail(s"Expected soft-fail decode to produce Annotated, not Abort: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Annotation case class has no _decodeCtx, argsPickle, args, or argList field" in {
        typeCheckFailure("val a: Tasty.Annotation = null.asInstanceOf[Tasty.Annotation]; a._decodeCtx")
        typeCheckFailure("val a: Tasty.Annotation = null.asInstanceOf[Tasty.Annotation]; a.argsPickle")
        typeCheckFailure("val a: Tasty.Annotation = null.asInstanceOf[Tasty.Annotation]; a.args")
        typeCheckFailure("val a: Tasty.Annotation = null.asInstanceOf[Tasty.Annotation]; a.argList")
        succeed
    }

    "Annotation case-class equality is structural" in {
        val symbol = makeSym("Foo")
        val tpe    = Tasty.Type.Named(Tasty.SymbolId(symbol.id))
        val ann1   = Tasty.Annotation(tpe, Chunk.empty, Tasty.Name(""))
        val ann2   = Tasty.Annotation(tpe, Chunk.empty, Tasty.Name(""))
        assert(ann1 == ann2, s"Expected equal Annotations but $ann1 != $ann2")
        assert(ann1.hashCode == ann2.hashCode, "Equal case class instances must have equal hashCodes")
        succeed
    }

end AnnotationEagerArgsTest
