package kyo

import kyo.internal.tasty.classfile.JavaSignatures
import kyo.internal.tasty.symbol.Interner

/** Tests for the JVM generic signature parser (JVMS §4.7.9.1).
  *
  * These tests are cross-platform (JVM, JS, Native) because they exercise pure string parsing.
  */
class JavaSignaturesTest extends Test:

    import AllowUnsafe.embrace.danger

    private val interner = new Interner(numShards = 8, initialShardCapacity = 16)

    // -------------------------------------------------------------------------
    // Test 13: parameterized type
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<String> produces Applied(Named, Chunk(Named))" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;>;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Applied(Tasty.Type.Named(baseSym), args) =>
                    assert(baseSym.name.asString == "java.util.List")
                    assert(args.length == 1)
                    args(0) match
                        case Tasty.Type.Named(argSym) =>
                            assert(argSym.name.asString == "java.lang.String")
                        case other =>
                            fail(s"Expected Named for type arg, got $other")
                    end match
                case other =>
                    fail(s"Expected Applied for List<String>, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 14: primitive array
    // -------------------------------------------------------------------------
    "parseFieldSignature of [I produces Array(Named(intSym))" in run {
        JavaSignatures.parseFieldSignature("[I", interner).map: tpe =>
            tpe match
                case Tasty.Type.Array(Tasty.Type.Named(elemSym)) =>
                    assert(elemSym.name.asString == "Int")
                case other =>
                    fail(s"Expected Array(Named(intSym)), got $other")
    }

    // -------------------------------------------------------------------------
    // Test 15: nested array of String
    // -------------------------------------------------------------------------
    "parseFieldSignature of [[Ljava/lang/String; produces Array(Array(Named))" in run {
        JavaSignatures.parseFieldSignature("[[Ljava/lang/String;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Array(Tasty.Type.Array(Tasty.Type.Named(elemSym))) =>
                    assert(elemSym.name.asString == "java.lang.String")
                case other =>
                    fail(s"Expected Array(Array(Named)), got $other")
    }

    // -------------------------------------------------------------------------
    // Test 16: covariant upper-bounded wildcard
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<+Number> produces Applied with Wildcard(Nothing, Named(Number))" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List<+Ljava/lang/Number;>;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Applied(_, args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Wildcard(lo, hi) =>
                            lo match
                                case Tasty.Type.Named(loSym) =>
                                    assert(loSym.name.asString == "Nothing", s"Expected Nothing lo, got ${loSym.name.asString}")
                                case other =>
                                    fail(s"Expected Named(Nothing) for lo, got $other")
                            end match
                            hi match
                                case Tasty.Type.Named(hiSym) =>
                                    assert(
                                        hiSym.name.asString == "java.lang.Number",
                                        s"Expected java.lang.Number hi, got ${hiSym.name.asString}"
                                    )
                                case other =>
                                    fail(s"Expected Named(Number) for hi, got $other")
                            end match
                        case other =>
                            fail(s"Expected Wildcard for covariant arg, got $other")
                case other =>
                    fail(s"Expected Applied with one arg, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 17: contravariant lower-bounded wildcard
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<-Number> produces Applied with Wildcard(Named(Number), Object)" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List<-Ljava/lang/Number;>;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Applied(_, args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Wildcard(lo, hi) =>
                            lo match
                                case Tasty.Type.Named(loSym) =>
                                    assert(
                                        loSym.name.asString == "java.lang.Number",
                                        s"Expected java.lang.Number lo, got ${loSym.name.asString}"
                                    )
                                case other =>
                                    fail(s"Expected Named(Number) for lo, got $other")
                            end match
                            hi match
                                case Tasty.Type.Named(hiSym) =>
                                    assert(hiSym.name.asString == "Object", s"Expected Object hi, got ${hiSym.name.asString}")
                                case other =>
                                    fail(s"Expected Named(Object) for hi, got $other")
                            end match
                        case other =>
                            fail(s"Expected Wildcard for contravariant arg, got $other")
                case other =>
                    fail(s"Expected Applied with one arg, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 18: raw type (no angle brackets => Named, not Applied)
    // -------------------------------------------------------------------------
    "parseFieldSignature of raw Ljava/util/List; produces Named (not Applied)" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Named(sym) =>
                    assert(sym.name.asString == "java.util.List")
                case other =>
                    fail(s"Expected Named for raw type, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 19: method signature with type parameter T
    // -------------------------------------------------------------------------
    "parseMethodSignature with type param T produces Function with TypeParam for T" in run {
        JavaSignatures.parseMethodSignature(
            "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)TT;",
            interner
        ).map: fnTpe =>
            // Result is Function. Check there is one param of type Applied(List, Chunk(Named(typeParam)))
            assert(fnTpe.params.length == 1, s"Expected 1 param, got ${fnTpe.params.length}")
            fnTpe.params(0) match
                case Tasty.Type.Applied(Tasty.Type.Named(listSym), args) if args.length == 1 =>
                    assert(listSym.name.asString == "java.util.List")
                    args(0) match
                        case Tasty.Type.Named(tSym) =>
                            assert(tSym.kind == Tasty.SymbolKind.TypeParam, s"Expected TypeParam, got ${tSym.kind}")
                        case other =>
                            fail(s"Expected Named(TypeParam) for T in List<T>, got $other")
                    end match
                case other =>
                    fail(s"Expected Applied(List, T), got $other")
            end match
    }

    // -------------------------------------------------------------------------
    // Test 20: corrupt signature => ClassfileFormatError
    // -------------------------------------------------------------------------
    "parseFieldSignature with unclosed < produces ClassfileFormatError" in run {
        Abort.run(JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;", interner)).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, reason, _)) =>
                    assert(reason.nonEmpty)
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
    }

end JavaSignaturesTest
