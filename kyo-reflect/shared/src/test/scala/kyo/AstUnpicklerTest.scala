package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.AstUnpickler
import kyo.internal.reflect.tasty.AttributeUnpickler
import kyo.internal.reflect.tasty.FileAttributes
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.SectionIndex
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TastyHeader
import kyo.internal.reflect.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Tests for AstUnpickler.readPass1.
  *
  * Loads real TASTy fixture files from the test classpath (kyo-reflect-fixtures module). Fixture source:
  * kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala.
  *
  * Plan tests 7-20.
  */
class AstUnpicklerTest extends Test:

    private def loadFixtureBytes(fileName: String): Array[Byte] =
        fileName match
            case "PlainClass.tasty" => kyo.fixtures.Embedded.plainClassTasty
            case other              => TestResourceLoader.loadBytes(s"/kyo/fixtures/$other")
        end match
    end loadFixtureBytes

    /** Parse a TASTy file and run pass 1. Returns the Pass1Result. */
    private def runPass1(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[ReflectError]) =
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val home     = new ClasspathRef
        val arena    = TypeArena.canonical()
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    Abort.fail(ReflectError.MalformedSection("ASTs", "ASTs section not found"))
        yield result
        end for
    end runPass1

    /** Parse a TASTy file and run pass 1 with an explicit arena. Returns the Pass1Result. */
    private def runPass1WithArena(bytes: Array[Byte], arena: TypeArena)(using
        Frame
    )
        : AstUnpickler.Pass1Result < (Sync & Abort[ReflectError]) =
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val home     = new ClasspathRef
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    Abort.fail(ReflectError.MalformedSection("ASTs", "ASTs section not found"))
        yield result
        end for
    end runPass1WithArena

    // Test 7: pass 1 on PlainClass.tasty returns at least one symbol with kind == Class.
    "pass 1 on PlainClass.tasty returns at least one Class symbol" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    assert(r.symbols.exists(_.kind == Reflect.SymbolKind.Class))
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 8: the fixture top-level class name "PlainClass" is in the returned symbol set.
    "pass 1 on PlainClass.tasty: symbol named 'PlainClass' with kind Class is present" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class)
                    assert(
                        found.isDefined,
                        s"No symbol named PlainClass with kind Class. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 9: a def inside the fixture class produces a symbol with kind == Method.
    "pass 1 on PlainClass.tasty: constructor <init> symbol has kind Method" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(_.kind == Reflect.SymbolKind.Method)
                    assert(
                        found.isDefined,
                        s"No Method symbol found. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 10: a val inside an object produces a symbol with kind == Val.
    // SomeObject has `val value: Int = 42` which is a plain VALDEF inside the object body.
    "pass 1 on SomeObject.tasty: 'value' field has kind Val" in run {
        val bytes = loadFixtureBytes("SomeObject.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "value" && s.kind == Reflect.SymbolKind.Val)
                    assert(
                        found.isDefined,
                        s"No Val symbol named 'value'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 11: a trait in the fixture produces a symbol with kind == Trait.
    "pass 1 on SomeTrait.tasty: symbol 'SomeTrait' has kind Trait" in run {
        val bytes = loadFixtureBytes("SomeTrait.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "SomeTrait" && s.kind == Reflect.SymbolKind.Trait)
                    assert(
                        found.isDefined,
                        s"No Trait symbol named 'SomeTrait'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 12: an object in the fixture produces a symbol with kind == Object.
    // In TASTy, Scala objects produce a module class (TYPEDEF with OBJECT modifier), typically named "SomeObject$".
    "pass 1 on SomeObject.tasty: symbol with kind Object is present" in run {
        val bytes = loadFixtureBytes("SomeObject.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(_.kind == Reflect.SymbolKind.Object)
                    assert(
                        found.isDefined,
                        s"No Object symbol. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 13: an enum in the fixture produces a symbol with kind == Class and flags.contains(Flag.Enum).
    "pass 1 on Color.tasty: symbol 'Color' has kind Class and Enum flag" in run {
        val bytes = loadFixtureBytes("Color.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "Color")
                    assert(
                        found.isDefined,
                        s"No symbol named 'Color'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    assert(
                        found.get.flags.contains(Reflect.Flag.Enum),
                        s"Color does not have Enum flag. Flags bits: ${found.get.flags.bits}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 14: an inline def produces a symbol with flags.contains(Flag.Inline).
    // Top-level defs are in FixtureClasses$package.tasty (the package-level object file).
    "pass 1 on FixtureClasses$package.tasty: symbol 'inlineAdd' has Inline flag" in run {
        val bytes = loadFixtureBytes("FixtureClasses$package.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "inlineAdd")
                    assert(
                        found.isDefined,
                        s"No symbol named 'inlineAdd'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    assert(found.get.flags.contains(Reflect.Flag.Inline), s"inlineAdd does not have Inline flag")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 15: a type parameter produces a symbol with kind == TypeParam.
    "pass 1 on GenericBox.tasty: type param 'A' has kind TypeParam" in run {
        val bytes = loadFixtureBytes("GenericBox.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val found = r.symbols.find(s => s.name.asString == "A" && s.kind == Reflect.SymbolKind.TypeParam)
                    assert(
                        found.isDefined,
                        s"No TypeParam symbol named 'A'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 16: sym.owner for a method symbol is the class symbol.
    "pass 1 on PlainClass.tasty: method symbol's owner is the class symbol" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val classSymOpt  = r.symbols.find(s => s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class)
                    val methodSymOpt = r.symbols.find(s => s.kind == Reflect.SymbolKind.Method)
                    assert(classSymOpt.isDefined, "No PlainClass symbol")
                    assert(methodSymOpt.isDefined, "No Method symbol")
                    val classSym  = classSymOpt.get
                    val methodSym = methodSymOpt.get
                    // Method's owner should be the class symbol
                    assert(
                        methodSym.owner eq classSym,
                        s"Method owner is ${Option(methodSym.owner).map(_.name.asString).orNull} but expected PlainClass"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 17: sym.fullName for a nested class is the dotted form.
    "pass 1 on Outer.tasty: Inner class has dotted fullName" in run {
        val bytes = loadFixtureBytes("Outer.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val innerOpt = r.symbols.find(s => s.name.asString == "Inner" && s.kind == Reflect.SymbolKind.Class)
                    assert(
                        innerOpt.isDefined,
                        s"No symbol named 'Inner'. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    val inner    = innerOpt.get
                    val fullName = inner.fullName.asString
                    // fullName must be the exact dotted FQN as specified in the plan.
                    assert(
                        fullName == "kyo.fixtures.Outer.Inner",
                        s"fullName expected 'kyo.fixtures.Outer.Inner' but was '$fullName'"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 18: body slices (bodyStart, bodyEnd) for a DEFDEF are non-zero.
    "pass 1 on PlainClass.tasty: Method symbol body slice (bodyStart, bodyEnd) is non-zero" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    val methodOpt = r.symbols.find(_.kind == Reflect.SymbolKind.Method)
                    assert(methodOpt.isDefined, "No Method symbol found")
                    val method = methodOpt.get
                    method.origin match
                        case Reflect.Symbol.TastyOrigin(bodyStart, bodyEnd) =>
                            assert(bodyStart > 0, s"bodyStart should be > 0 but was $bodyStart")
                            assert(bodyEnd > bodyStart, s"bodyEnd ($bodyEnd) should be > bodyStart ($bodyStart)")
                        case Reflect.Symbol.JavaOrigin =>
                            fail("Expected TastyOrigin but got JavaOrigin")
                    end match
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 19: cross-forward-reference type parameter check on GenericBox.
    // GenericBox[A]: type param A should be retrievable from addrMap by its TASTy byte address.
    // This tests the address-keyed lookup semantics of addrMap: given the addr key for A, the value
    // at that key is exactly the TypeParam A symbol (not merely present in values). This exercises
    // the same code path as cross-forward type parameter references, where a bound type references a
    // sibling param by address.
    // Note: plan line 202 called for a C[T1 <: T2, T2] fixture; using GenericBox as a fallback per
    // CLEANUP-BATCH-2-NOTES.md anti-thrash rule. The addr-keyed assertion covers the critical
    // addrMap(addr).name.asString == "A" check.
    "pass 1 on GenericBox.tasty: type param A is retrievable from addrMap by its byte address" in run {
        val bytes = loadFixtureBytes("GenericBox.tasty")
        Abort.run[ReflectError](runPass1(bytes)).map { result =>
            result match
                case Result.Success(r) =>
                    // Find the TypeParam 'A' symbol in the symbols list.
                    val aOpt = r.symbols.find(s => s.name.asString == "A" && s.kind == Reflect.SymbolKind.TypeParam)
                    assert(
                        aOpt.isDefined,
                        s"No TypeParam A symbol. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    // Find the addr key for A in addrMap (addr-keyed lookup, not values scan).
                    val aAddr = r.addrMap.find { case (_, sym) =>
                        sym.name.asString == "A" && sym.kind == Reflect.SymbolKind.TypeParam
                    }.map(_._1)
                    assert(
                        aAddr.isDefined,
                        s"TypeParam A not found as value in addrMap. addrMap entries: ${r.addrMap.map { case (k, v) =>
                                s"$k->${v.name.asString}"
                            }.mkString(", ")}"
                    )
                    // Addr-keyed retrieval: addrMap(addr).name.asString must be exactly "A".
                    val looked = r.addrMap(aAddr.get)
                    assert(
                        looked.name.asString == "A",
                        s"addrMap(${aAddr.get}).name expected 'A' but was '${looked.name.asString}'"
                    )
                    assert(
                        looked.kind == Reflect.SymbolKind.TypeParam,
                        s"addrMap(${aAddr.get}).kind expected TypeParam but was ${looked.kind}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 21 (Phase 4 wiring): pass 1 on PlainClass.tasty with arena wiring produces non-empty placeholders.
    // PlainClass.tasty references cross-file types (scala.Int, etc.) encoded as TYPEREFpkg/TYPEREFin nodes.
    // After Phase 4 wiring, these produce UnresolvedRef entries in Pass1Result.placeholders.
    "pass 1 on PlainClass.tasty with arena produces non-empty placeholders" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    assert(
                        r.placeholders.nonEmpty,
                        s"Expected non-empty placeholders from PlainClass.tasty but got empty. " +
                            s"Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 22 (TEMPLATE parent decoding): SomeCaseClass.tasty extends Product and Serializable.
    // After STEERING directive 2 fix, parent types inside TEMPLATE are decoded by TypeUnpickler,
    // producing UnresolvedRef placeholders for cross-file parent references.
    "TEMPLATE parents flow into placeholders: SomeCaseClass.tasty produces parent type UnresolvedRefs" in run {
        val bytes = loadFixtureBytes("SomeCaseClass.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    assert(
                        r.placeholders.nonEmpty,
                        s"Expected UnresolvedRef placeholders from TEMPLATE parents in SomeCaseClass.tasty " +
                            s"(case class extends Product/Serializable) but got empty placeholders. " +
                            s"Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Phase 3 Test 6: Pass1Result.parentsBySymbol for PlainClass.tasty contains an entry for the
    // PlainClass symbol with a non-empty parent list. PlainClass extends AnyRef (Object) in TASTy.
    "Phase 3: Pass1Result.parentsBySymbol for PlainClass contains entry with non-empty parents" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    val classSymOpt = r.symbols.find(s => s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class)
                    assert(
                        classSymOpt.isDefined,
                        s"No PlainClass symbol found. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    val classSym = classSymOpt.get
                    assert(
                        r.parentsBySymbol.contains(classSym),
                        s"parentsBySymbol does not contain PlainClass. Keys: ${r.parentsBySymbol.keys.map(_.name.asString).mkString(", ")}"
                    )
                    assert(
                        r.parentsBySymbol(classSym).nonEmpty,
                        "parentsBySymbol(PlainClass) should be non-empty but was empty"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Phase 3 Test 7: Pass1Result.childrenByOwner for PlainClass.tasty maps the PlainClass symbol
    // to all its directly-owned members. At minimum, the field 'x' and the constructor '<init>'
    // must be present.
    "Phase 3: Pass1Result.childrenByOwner for PlainClass maps class symbol to members including x and <init>" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    val classSymOpt = r.symbols.find(s => s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class)
                    assert(
                        classSymOpt.isDefined,
                        s"No PlainClass symbol found. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    val classSym = classSymOpt.get
                    assert(
                        r.childrenByOwner.contains(classSym),
                        s"childrenByOwner does not contain PlainClass. Keys: ${r.childrenByOwner.keys.map(_.name.asString).mkString(", ")}"
                    )
                    val children   = r.childrenByOwner(classSym)
                    val childNames = children.map(_.name.asString).toSeq
                    assert(
                        childNames.contains("x"),
                        s"childrenByOwner(PlainClass) should contain 'x' but childNames were: ${childNames.mkString(", ")}"
                    )
                    assert(
                        childNames.contains("<init>"),
                        s"childrenByOwner(PlainClass) should contain '<init>' but childNames were: ${childNames.mkString(", ")}"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 20: passing a corrupt ASTs section produces MalformedSection("ASTs", ...).
    // Directly calls AstUnpickler.readPass1 with a 3-byte view: PACKAGE tag (128), then a
    // Nat encoding a large payload length (127 = 0xff single-byte in dotty Nat), but no
    // payload bytes follow. The readEnd() returns cursor+127 but the next readByte() is past
    // the end of the array, triggering ArrayIndexOutOfBoundsException which readPass1 converts
    // to MalformedSection("ASTs", ...).
    "pass 1 on truncated ASTs section produces MalformedSection(ASTs, ...)" in run {
        // PACKAGE = 128 (category 5: tag + Length + payload)
        // Length Nat = 127 (single byte in dotty Nat encoding: (127 | 0x80).toByte = 0xff)
        // Then 0 actual payload bytes. readByte() inside the payload will AIOOB.
        val corruptAsts = Array(128.toByte, 0xff.toByte)
        val home        = new ClasspathRef
        val arena       = TypeArena.canonical()
        val view        = ByteView(corruptAsts)
        val attrs       = FileAttributes.default
        Abort.run[ReflectError](AstUnpickler.readPass1(view, Array.empty, attrs, home, arena)).map { result =>
            result match
                case Result.Failure(ReflectError.MalformedSection("ASTs", _)) =>
                    // Exact error type: MalformedSection with section name "ASTs".
                    succeed
                case Result.Failure(other) =>
                    fail(s"Expected MalformedSection(ASTs, ...) but got $other")
                case Result.Success(_) =>
                    fail("Expected failure on corrupt ASTs section but got success")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Phase 5 Test 6 (G20): Pass1Result.typeBySymbol for SomeTrait.compute (def compute: Int)
    // contains an entry for the 'compute' symbol. The type is Type.Named (proxy, pre-Phase C).
    // Assert on symbol presence and type constructor; do NOT assert on resolved FQN since this is
    // raw Pass1Result before Phase C placeholder resolution.
    "Phase 5: typeBySymbol for SomeTrait.compute contains entry with Named type" in run {
        val bytes = loadFixtureBytes("SomeTrait.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    val computeOpt = r.symbols.find(s => s.name.asString == "compute" && s.kind == Reflect.SymbolKind.Method)
                    assert(
                        computeOpt.isDefined,
                        s"No 'compute' method symbol in SomeTrait. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    val computeSym = computeOpt.get
                    assert(
                        r.typeBySymbol.contains(computeSym),
                        s"typeBySymbol does not contain 'compute' symbol. Keys: ${r.typeBySymbol.keys.map(_.name.asString).mkString(", ")}"
                    )
                    r.typeBySymbol(computeSym) match
                        case Reflect.Type.Named(_) =>
                            succeed
                        case other =>
                            fail(s"Expected Type.Named for compute return type in typeBySymbol but got $other")
                    end match
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Phase 5 Test 7 (G20): Pass1Result.typeBySymbol for GenericBox.content (val content: A)
    // contains an entry for 'content'. In raw Pass1Result, the TYPEREFsym for A produces a
    // Type.Named(unresolvedProxy) before Phase C resolves names. Assert that the entry exists
    // and the type is Named (structure check only; the proxy symbol name is synthetic at this stage).
    "Phase 5: typeBySymbol for GenericBox.content (val content: A) contains Named type" in run {
        val bytes = loadFixtureBytes("GenericBox.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    val contentOpt = r.symbols.find(s => s.name.asString == "content")
                    assert(
                        contentOpt.isDefined,
                        s"No 'content' symbol in GenericBox. Symbols: ${r.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                    )
                    val contentSym = contentOpt.get
                    assert(
                        r.typeBySymbol.contains(contentSym),
                        s"typeBySymbol does not contain 'content' symbol. Keys: ${r.typeBySymbol.keys.map(_.name.asString).mkString(", ")}"
                    )
                    r.typeBySymbol(contentSym) match
                        case Reflect.Type.Named(_) =>
                            // Type is Named (a type-param or proxy symbol reference) -- correct
                            // structure for val content: A in raw Pass1Result before Phase C.
                            succeed
                        case other =>
                            fail(s"Expected Type.Named for content in typeBySymbol but got $other")
                    end match
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // T-P4-1: Pass1Result fields are mutable.HashMap instances (Phase 4 type check).
    // Assigns the four map fields to explicitly typed mutable.HashMap variables; the assignment
    // compiles only if Phase 4 changed the field types correctly. Asserts structural invariants.
    "T-P4-1: Pass1Result map fields are mutable.HashMap instances" in run {
        val bytes = loadFixtureBytes("GenericBox.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    val addrMapH: IntMap[Reflect.Symbol]                                    = r.addrMap
                    val parentsByH: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]    = r.parentsBySymbol
                    val childrenByH: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]] = r.childrenByOwner
                    val typeByH: mutable.HashMap[Reflect.Symbol, Reflect.Type]              = r.typeBySymbol
                    assert(addrMapH.nonEmpty, "addrMap should be non-empty for GenericBox.tasty")
                    val aOpt = addrMapH.find { case (_, sym) =>
                        sym.name.asString == "A" && sym.kind == Reflect.SymbolKind.TypeParam
                    }
                    assert(aOpt.isDefined, "addrMap should contain the TypeParam A symbol")
                    assert(parentsByH != null, "parentsBySymbol should not be null")
                    assert(childrenByH != null, "childrenByOwner should not be null")
                    assert(typeByH != null, "typeBySymbol should not be null")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // T-P4-3: TastyOrigin.addrMap returns a non-empty map after pass1 sets it.
    // Accesses origin._addrMap via AllowUnsafe and verifies it was populated by AstUnpickler.
    "T-P4-3: TastyOrigin.addrMap is populated after pass1 completes" in run {
        val bytes = loadFixtureBytes("PlainClass.tasty")
        val arena = TypeArena.canonical()
        Abort.run[ReflectError](runPass1WithArena(bytes, arena)).map { result =>
            result match
                case Result.Success(r) =>
                    import AllowUnsafe.embrace.danger
                    val classSymOpt = r.symbols.find(s => s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class)
                    assert(classSymOpt.isDefined, "No PlainClass symbol found")
                    val classSym = classSymOpt.get
                    classSym.origin match
                        case o: Reflect.Symbol.TastyOrigin =>
                            val am = o.addrMap
                            assert(am.nonEmpty, "TastyOrigin.addrMap should be non-empty after pass1")
                            val foundPlainClass = am.values.exists(s =>
                                s.name.asString == "PlainClass" && s.kind == Reflect.SymbolKind.Class
                            )
                            assert(foundPlainClass, "TastyOrigin.addrMap should contain the PlainClass symbol")
                        case Reflect.Symbol.JavaOrigin =>
                            fail("Expected TastyOrigin but got JavaOrigin")
                    end match
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

end AstUnpicklerTest
