package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** INV-LOADING-SYMBOL enforcement: no SymbolId.value == -1 survives in produced public ADTs.
  *
  * fix: after finalizeMerge, `Named(SymbolId(-1))` sentinels are filtered out of
  * parentTypes, declaredType, and annotations. This test verifies that invariant holds against
  * a real fixture classpath loaded cold via ClasspathOrchestrator.
  *
  * Covers:
  *   noSentinelIdInParentTypes: parentTypes Chunk contains no Named(SymbolId(-1))
  *   noSentinelIdInClassLikeAnnotations: ClassLike annotation tycon types are not Named(SymbolId(-1))
  */
class SentinelIdLeakInvariantTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    final class MemFS(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer { files.remove(from); files(to) = bytes }
                case None => Abort.fail(TastyError.SnapshotIoError(s"$from not found"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq))
        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))
    end MemFS

    /** Walk a Type recursively and return all SymbolId values found in Named and ThisType nodes. */
    private def namedIdsInType(t: Tasty.Type): List[Int] =
        t match
            case Tasty.Type.Named(sid)             => List(sid.value)
            case Tasty.Type.Applied(base, args)    => namedIdsInType(base) ++ args.flatMap(namedIdsInType).toList
            case Tasty.Type.Function(ps, r)        => ps.flatMap(namedIdsInType).toList ++ namedIdsInType(r)
            case Tasty.Type.ContextFunction(ps, r) => ps.flatMap(namedIdsInType).toList ++ namedIdsInType(r)
            case Tasty.Type.ByName(u)              => namedIdsInType(u)
            case Tasty.Type.Repeated(e)            => namedIdsInType(e)
            case Tasty.Type.Array(e)               => namedIdsInType(e)
            case Tasty.Type.AndType(l, r)          => namedIdsInType(l) ++ namedIdsInType(r)
            case Tasty.Type.OrType(l, r)           => namedIdsInType(l) ++ namedIdsInType(r)
            case Tasty.Type.Refinement(p, _, i)    => namedIdsInType(p) ++ namedIdsInType(i)
            case Tasty.Type.Annotated(u, ann)      => namedIdsInType(u) ++ namedIdsInType(ann.annotationType)
            case Tasty.Type.SuperType(th, u)       => namedIdsInType(th) ++ namedIdsInType(u)
            case Tasty.Type.Wildcard(lo, hi)       => namedIdsInType(lo) ++ namedIdsInType(hi)
            case Tasty.Type.MatchType(b, s, cs)    => namedIdsInType(b) ++ namedIdsInType(s) ++ cs.flatMap(namedIdsInType).toList
            case Tasty.Type.FlexibleType(u)        => namedIdsInType(u)
            case Tasty.Type.MatchCase(p, r)        => namedIdsInType(p) ++ namedIdsInType(r)
            case Tasty.Type.Rec(p)                 => namedIdsInType(p)
            case Tasty.Type.RecThis(rec)           => namedIdsInType(rec)
            case Tasty.Type.Skolem(u)              => namedIdsInType(u)
            case Tasty.Type.TermRef(pref, _)       => namedIdsInType(pref)
            case Tasty.Type.TypeRef(pref, _)       => namedIdsInType(pref)
            case Tasty.Type.TypeLambda(_, body)    => namedIdsInType(body)
            case Tasty.Type.Bounds(lo, hi)         => namedIdsInType(lo) ++ namedIdsInType(hi)
            case Tasty.Type.ThisType(sid)          => List(sid.value)
            case _                                 => Nil

    /** Load the fixture classpath cold (no snapshot). */
    private def loadFixtureCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemFS()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end loadFixtureCp

    // noSentinelIdInParentTypes
    "noSentinelIdInParentTypes: no Named(SymbolId(-1)) in any symbol parentTypes after cold load" in {
        Scope.run:
            Abort.run[TastyError](loadFixtureCp.map: cp =>
                var violations = List.empty[String]
                cp.symbols.foreach: sym =>
                    sym match
                        case c: Tasty.Symbol.ClassLike =>
                            c.parentTypes.foreach: pt =>
                                val ids = namedIdsInType(pt)
                                ids.foreach: v =>
                                    if v == -1 then
                                        violations ::= s"${c.name.asString}.parentType=$pt has Named(SymbolId(-1))"
                        case _ => ()
                violations).map:
                case Result.Success(violations) =>
                    assert(violations.isEmpty, s"Sentinel leaks found:\n${violations.mkString("\n")}")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // noSentinelIdInClassLikeAnnotations
    "noSentinelIdInClassLikeAnnotations: no Named(SymbolId(-1)) in ClassLike annotation types after cold load" in {
        Scope.run:
            Abort.run[TastyError](loadFixtureCp.map: cp =>
                var violations = List.empty[String]
                cp.symbols.foreach: sym =>
                    sym match
                        case c: Tasty.Symbol.ClassLike =>
                            c.annotations.foreach: ann =>
                                val ids = namedIdsInType(ann.annotationType)
                                ids.foreach: v =>
                                    if v == -1 then
                                        violations ::= s"${c.name.asString}.annotation.type=${ann.annotationType} has Named(SymbolId(-1))"
                        case _ => ()
                violations).map:
                case Result.Success(violations) =>
                    assert(violations.isEmpty, s"Sentinel leaks found:\n${violations.mkString("\n")}")
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end SentinelIdLeakInvariantTest
