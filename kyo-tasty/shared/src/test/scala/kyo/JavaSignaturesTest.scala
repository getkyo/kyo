package kyo

import kyo.internal.tasty.classfile.JavaSignatures
import kyo.internal.tasty.symbol.Interner

/** Tests for the JVM generic signature parser (JVMS §4.7.9.1).
  *
  * These tests are cross-platform (JVM, JS, Native) because they exercise pure string parsing.
  *
  * plan: phase-05; Named(id) no longer carries a Symbol directly. Tests verify structural shape (Applied/Named/Wildcard/Array) without
  * accessing symbol names or kinds. Phase 09 restores name-verification once cp.symbol(id).name is available in resolution methods.
  */
class JavaSignaturesTest extends Test:

    import AllowUnsafe.embrace.danger

    private val interner = Interner.init(numShards = 8, initialShardCapacity = 16)

    // -------------------------------------------------------------------------
    // Test 13: parameterized type
    // -------------------------------------------------------------------------
    "parseFieldSignature of List<String> produces Applied(Named, Chunk(Named))" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;>;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Applied(Tasty.Type.Named(_), args) =>
                    // plan: phase-05; name check deferred to Phase 09.
                    assert(args.length == 1, s"Expected 1 arg, got ${args.length}")
                    args(0) match
                        case Tasty.Type.Named(_) =>
                            assert(true)
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
                case Tasty.Type.Array(Tasty.Type.Named(_)) =>
                    // plan: phase-05; name check (Int) deferred to Phase 09.
                    assert(true)
                case other =>
                    fail(s"Expected Array(Named(intSym)), got $other")
    }

    // -------------------------------------------------------------------------
    // Test 15: nested array of String
    // -------------------------------------------------------------------------
    "parseFieldSignature of [[Ljava/lang/String; produces Array(Array(Named))" in run {
        JavaSignatures.parseFieldSignature("[[Ljava/lang/String;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Array(Tasty.Type.Array(Tasty.Type.Named(_))) =>
                    // plan: phase-05; name check (java.lang.String) deferred to Phase 09.
                    assert(true)
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
                        case Tasty.Type.Wildcard(Tasty.Type.Named(_), Tasty.Type.Named(_)) =>
                            // plan: phase-05; name checks (Nothing, java.lang.Number) deferred to Phase 09.
                            assert(true)
                        case other =>
                            fail(s"Expected Wildcard(Named,Named) for covariant arg, got $other")
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
                        case Tasty.Type.Wildcard(Tasty.Type.Named(_), Tasty.Type.Named(_)) =>
                            // plan: phase-05; name checks (java.lang.Number, Object) deferred to Phase 09.
                            assert(true)
                        case other =>
                            fail(s"Expected Wildcard(Named,Named) for contravariant arg, got $other")
                case other =>
                    fail(s"Expected Applied with one arg, got $other")
    }

    // -------------------------------------------------------------------------
    // Test 18: raw type (no angle brackets => Named, not Applied)
    // -------------------------------------------------------------------------
    "parseFieldSignature of raw Ljava/util/List; produces Named (not Applied)" in run {
        JavaSignatures.parseFieldSignature("Ljava/util/List;", interner).map: tpe =>
            tpe match
                case Tasty.Type.Named(_) =>
                    // plan: phase-05; name check (java.util.List) deferred to Phase 09.
                    assert(true)
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
            // Result is Function. Check there is one param of type Applied(List, Chunk(Named(typeParam))).
            // plan: phase-05; name and kind checks deferred to Phase 09 (Named carries SymbolId, not Symbol).
            assert(fnTpe.params.length == 1, s"Expected 1 param, got ${fnTpe.params.length}")
            fnTpe.params(0) match
                case Tasty.Type.Applied(Tasty.Type.Named(_), args) if args.length == 1 =>
                    args(0) match
                        case Tasty.Type.Named(_) =>
                            assert(true)
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
    "parseFieldSignature with unclosed < produces ClassfileFormatError" in run {
        Abort.run(JavaSignatures.parseFieldSignature("Ljava/util/List<Ljava/lang/String;", interner)).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, reason, _)) =>
                    assert(reason.nonEmpty)
                case other =>
                    fail(s"Expected ClassfileFormatError, got $other")
    }

end JavaSignaturesTest
