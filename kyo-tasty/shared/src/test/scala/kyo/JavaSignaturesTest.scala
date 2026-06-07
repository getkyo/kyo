package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.classfile.JavaSignatures

/** Tests for the JVM generic signature parser (JVMS §4.7.9.1).
  *
  * These tests are cross-platform (JVM, JS, Native) because they exercise pure string parsing.
  *
  * plan: phase-05; Named(id) no longer carries a Symbol directly. Tests verify structural shape (Applied/Named/Wildcard/Array) without
  * accessing symbol names or kinds. restores name-verification once cp.symbol(id).name is available in resolution methods.
  */
class JavaSignaturesTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // -------------------------------------------------------------------------
    // Test 13: parameterized type
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<String> produces Applied(Named, Chunk(Named))" in {
        JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;>;").map: tpe =>
            tpe match
                case Tasty.Type.Applied(Tasty.Type.Named(_), args) =>
                    // plan: phase-05; name check deferred to.
                    assert(args.length == 1, s"Expected 1 arg, got ${args.length}")
                    args(0) match
                        case Tasty.Type.Named(innerId) =>
                            assert(innerId.value == -1, s"Stub symbol must carry SymbolId(-1), got ${innerId.value}")
                        case other =>
                            fail(s"Expected Named for type arg, got $other")
                    end match
                case other =>
                    fail(s"Expected Applied for List<String>, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 14: primitive array
    // -------------------------------------------------------------------------
    "parseFieldSignature of [I produces Array(Named(intSym))" in {
        JavaSignatures.parseFieldSignature("[I").map: tpe =>
            tpe match
                case Tasty.Type.Array(Tasty.Type.Named(elemId)) =>
                    assert(elemId.value == -1, s"Primitive int stub must carry SymbolId(-1), got ${elemId.value}")
                case other =>
                    fail(s"Expected Array(Named(intSym)), got $other")
    }

    // -------------------------------------------------------------------------
    // Test 15: nested array of String
    // -------------------------------------------------------------------------
    "parseFieldSignature of [[Ljava/lang/String; produces Array(Array(Named))" in {
        JavaSignatures.parseFieldSignature("[[Ljava/lang/String;").map: tpe =>
            tpe match
                case Tasty.Type.Array(Tasty.Type.Array(Tasty.Type.Named(elemId))) =>
                    assert(elemId.value == -1, s"String stub must carry SymbolId(-1), got ${elemId.value}")
                case other =>
                    fail(s"Expected Array(Array(Named)), got $other")
    }

    // -------------------------------------------------------------------------
    // Test 16: covariant upper-bounded wildcard
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<+Number> produces Applied with Wildcard(Nothing, Named(Number))" in {
        JavaSignatures.parseFieldSignature("Ljava/util/List<+Ljava/lang/Number;>;").map: tpe =>
            tpe match
                case Tasty.Type.Applied(_, args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Wildcard(Tasty.Type.Named(lowerId), Tasty.Type.Named(upperId)) =>
                            assert(lowerId.value == -1, s"Nothing lower bound stub must carry SymbolId(-1), got ${lowerId.value}")
                            assert(upperId.value == -1, s"Number upper bound stub must carry SymbolId(-1), got ${upperId.value}")
                        case other =>
                            fail(s"Expected Wildcard(Named,Named) for covariant arg, got $other")
                case other =>
                    fail(s"Expected Applied with one arg, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 17: contravariant lower-bounded wildcard
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<-Number> produces Applied with Wildcard(Named(Number), Object)" in {
        JavaSignatures.parseFieldSignature("Ljava/util/List<-Ljava/lang/Number;>;").map: tpe =>
            tpe match
                case Tasty.Type.Applied(_, args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Wildcard(Tasty.Type.Named(lowerId), Tasty.Type.Named(upperId)) =>
                            assert(lowerId.value == -1, s"Number lower bound stub must carry SymbolId(-1), got ${lowerId.value}")
                            assert(upperId.value == -1, s"Object upper bound stub must carry SymbolId(-1), got ${upperId.value}")
                        case other =>
                            fail(s"Expected Wildcard(Named,Named) for contravariant arg, got $other")
                case other =>
                    fail(s"Expected Applied with one arg, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 18: raw type (no angle brackets => Named, not Applied)
    // -------------------------------------------------------------------------
    "parseFieldSignature of raw Ljava/util/List; produces Named (not Applied)" in {
        JavaSignatures.parseFieldSignature("Ljava/util/List;").map: tpe =>
            tpe match
                case Tasty.Type.Named(rawId) =>
                    assert(rawId.value == -1, s"Raw type stub must carry SymbolId(-1), got ${rawId.value}")
                case other =>
                    fail(s"Expected Named for raw type, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 19: method signature with type parameter T
    // -------------------------------------------------------------------------
    "parseMethodSignature with type param T produces Function with TypeParam for T" in {
        JavaSignatures.parseMethodSignature(
            "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)TT;"
        ).map: fnTpe =>
            // Result is Function. Check there is one param of type Applied(List, Chunk(Named(typeParam))).
            // plan: phase-05; name and kind checks deferred to (Named carries SymbolId, not Symbol).
            assert(fnTpe.params.length == 1, s"Expected 1 param, got ${fnTpe.params.length}")
            fnTpe.params(0) match
                case Tasty.Type.Applied(Tasty.Type.Named(_), args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Named(typeParamId) =>
                            assert(typeParamId.value == -1, s"Type param T stub must carry SymbolId(-1), got ${typeParamId.value}")
                        case other =>
                            fail(s"Expected Named for T in List<T>, got $other")
                    end match
                case other =>
                    fail(s"Expected Applied(List, T), got $other")
            end match
    }

    // -------------------------------------------------------------------------
    // Test 20: corrupt signature => ClassfileFormatError
    // -------------------------------------------------------------------------
    "parseFieldSignature with unclosed < produces ClassfileFormatError" in {
        Abort.run(JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;")).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, reason, _)) =>
                    // An unclosed `<` must surface in the reason: either an explicit "unclosed" message
                    // or the offending character itself. Assert the substring rather than mere non-emptiness.
                    assert(
                        reason.contains("unclosed") || reason.contains("<") || reason.contains("EOF"),
                        s"""Expected reason to mention unclosed type-arg list ("unclosed"/"<"/"EOF") but got: "$reason""""
                    )
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
    }

end JavaSignaturesTest
