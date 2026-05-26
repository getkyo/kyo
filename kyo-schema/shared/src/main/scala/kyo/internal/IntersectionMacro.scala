package kyo.internal

import kyo.*
import kyo.Codec.Reader
import kyo.Codec.Writer
import scala.quoted.*

/** Macro for deriving `Schema[T]` where `T` is an intersection type `A & B`.
  *
  * Four sub-cases (see kyo-schema/improvement-plan/followup/analysis.md, Item 6):
  *   1. ConcreteAndMarker — one half is a concrete data-carrying type, the other is a pure-interface trait with no fields. Delegate
  *      encode/decode to the data half's `Schema`.
  *   2. BothHaveFields — both halves contribute abstract `def` / `val` members. Collect the union of fields (dedupe by name; compile-error
  *      on same-name-different-type), synthesize an anonymous class extending both halves via `Symbol.newClass`, and encode field-by-field
  *      via each member's summoned `Schema`.
  *   3. Refinement — the dealiased type is a `Refinement(parent, _, _)`; recurse on `parent`.
  *   4. Unconstructible — two unrelated case classes that cannot be combined. Compile error.
  *
  * Note: anonymous-class synthesis via `Symbol.newClass` extends abstract traits with stored vals; it cannot extend two unrelated concrete
  * case classes (Scala 3 disallows mixin of multiple concrete class parents). That sub-case lands in [[Classification.Unconstructible]].
  */
object IntersectionMacro:

    def derive[T: Type](using Quotes): Expr[Schema[T]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val tpe                        = TypeRepr.of[T].dealias
        peel(tpe) match
            case Some(parent) =>
                parent.asType match
                    case '[p] =>
                        val summoned = Expr.summon[Schema[p]].getOrElse(
                            report.errorAndAbort(
                                s"No given Schema[${parent.show}] for refinement parent. Define a Schema for ${parent.show} or use .transform."
                            )
                        )
                        '{ $summoned.asInstanceOf[Schema[T]] }
            case None =>
                val halves = flattenAnd(tpe)
                // Reject genuinely uninhabited intersections: two or more halves where any half is
                // a final non-trait class or a primitive value type (Int, Long, etc.) — no value can
                // simultaneously inhabit two such concrete types. e.g. `String & Int`, `Int & Long`.
                if halves.sizeIs >= 2 then
                    val concreteHalves = halves.filter(h => isConcreteUninhabitable(h))
                    val nonTraitHalves = halves.filter(h => !h.typeSymbol.flags.is(Flags.Trait))
                    if concreteHalves.nonEmpty && nonTraitHalves.sizeIs >= 2 then
                        report.errorAndAbort(
                            s"Schema.derived[${tpe.show}]: intersection types have no general constructor for unrelated concrete types. " +
                                "Define a concrete class extending all halves, or build a Schema with .transform."
                        )
                    end if
                end if
                val withFields  = halves.map(h => (h, fieldMembers(h)))
                val dataHalves  = withFields.filter(_._2.nonEmpty)
                val markerCount = withFields.count(_._2.isEmpty)

                if dataHalves.isEmpty then
                    report.errorAndAbort(
                        s"Schema.derived[${tpe.show}]: intersection has no data-carrying half."
                    )
                else if dataHalves.sizeIs == 1 && markerCount >= 1 then
                    // Case 1: one concrete data half, the rest are pure markers — delegate to the data half's Schema.
                    deriveViaHalf[T](dataHalves.head._1)
                else
                    // Case 4 detection: two or more concrete case classes cannot be combined into one instance.
                    val caseClassHalves = dataHalves.filter(p => p._1.typeSymbol.flags.is(Flags.Case))
                    if caseClassHalves.sizeIs >= 2 then
                        report.errorAndAbort(
                            s"Schema.derived[${tpe.show}]: intersection of two unrelated case classes has no constructor. " +
                                "Define a concrete class extending both, or build a Schema with .transform."
                        )
                    else
                        // Case 2: synthesize anonymous class.
                        deriveSynthesized[T](dataHalves.map(_._1))
                    end if
                end if
        end match
    end derive

    /** Single-step refinement passthrough. */
    private def peel(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        tpe.dealias match
            case Refinement(parent, _, _) => Some(parent)
            case _                        => None
    end peel

    /** Detects halves that, combined with another non-trait half, cannot have any inhabitant. Final non-trait classes (String, UUID, ...) and
      * primitive value types (Int, Long, Boolean, Char, ...) can never be combined with an unrelated concrete type.
      */
    private def isConcreteUninhabitable(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.typeSymbol
        val isPrimitive: Boolean =
            tpe =:= TypeRepr.of[Int] || tpe =:= TypeRepr.of[Long] || tpe =:= TypeRepr.of[Short] ||
                tpe =:= TypeRepr.of[Byte] || tpe =:= TypeRepr.of[Char] || tpe =:= TypeRepr.of[Float] ||
                tpe =:= TypeRepr.of[Double] || tpe =:= TypeRepr.of[Boolean] || tpe =:= TypeRepr.of[Unit]
        val isFinalNonTrait = sym.flags.is(Flags.Final) && !sym.flags.is(Flags.Trait)
        isPrimitive || isFinalNonTrait
    end isConcreteUninhabitable

    private def flattenAnd(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        def go(t: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] = t.dealias match
            case AndType(a, b) => go(a) ++ go(b)
            case other         => List(other)
        go(tpe)
    end flattenAnd

    /** Field-like members of a type. For case classes returns caseFields; for traits returns abstract / concrete public no-arg `def` and
      * `val` members owned by the type itself (or its non-Object ancestors).
      */
    private def fieldMembers(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr): List[(String, quotes.reflect.TypeRepr)] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym                        = tpe.typeSymbol
        if sym.flags.is(Flags.Case) && sym.isClassDef then
            sym.caseFields.map(f => (f.name, tpe.memberType(f)))
        else
            val objectSym = TypeRepr.of[Object].typeSymbol
            val anyRefSym = TypeRepr.of[AnyRef].typeSymbol
            val anySym    = TypeRepr.of[Any].typeSymbol
            def excluded(owner: Symbol): Boolean =
                owner == objectSym || owner == anyRefSym || owner == anySym
            // `tpe.memberType(m)` for a `def x: T` returns a MethodType / ByNameType wrapping the result;
            // strip those wrappers to recover the value type the field actually carries.
            def resultOf(t: TypeRepr): TypeRepr = t match
                case MethodType(_, _, res) => resultOf(res)
                case PolyType(_, _, res)   => resultOf(res)
                case ByNameType(res)       => resultOf(res)
                case other                 => other
            val vals = sym.fieldMembers.filter(f => !excluded(f.owner)).map(f => (f.name, resultOf(tpe.memberType(f))))
            val defs = sym.methodMembers
                .filter(m => !excluded(m.owner) && m.paramSymss.flatten.isEmpty && !m.flags.is(Flags.Synthetic))
                .map(m => (m.name, resultOf(tpe.memberType(m))))
            // Dedupe by name (vals take precedence over abstract defs of the same name).
            val seen = scala.collection.mutable.Set.empty[String]
            (vals ++ defs).filter { (n, _) =>
                if seen(n) then false
                else
                    seen += n; true
            }
        end if
    end fieldMembers

    /** Case 1: delegate fully to the data half's Schema. */
    private def deriveViaHalf[T: Type](using
        Quotes
    )(dataHalf: quotes.reflect.TypeRepr): Expr[Schema[T]] =
        import quotes.reflect.*
        dataHalf.asType match
            case '[h] =>
                val summoned = Expr.summon[Schema[h]].getOrElse(
                    report.errorAndAbort(
                        s"No given Schema[${dataHalf.show}] for intersection data half. Define a Schema for ${dataHalf.show}."
                    )
                )
                '{ $summoned.asInstanceOf[Schema[T]] }
        end match
    end deriveViaHalf

    /** Case 2: synthesize an anonymous class extending the (trait) halves with one stored val per merged field, and emit a Schema that
      * encodes each field by name via its summoned `Schema` and decodes back into a fresh anonymous instance.
      */
    private def deriveSynthesized[T: Type](using
        Quotes
    )(halves: List[quotes.reflect.TypeRepr]): Expr[Schema[T]] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived

        // Collect the union of fields across all halves, deduping same-name-same-type and erroring on collisions.
        val merged: List[(String, TypeRepr)] =
            val acc = scala.collection.mutable.LinkedHashMap.empty[String, TypeRepr]
            for h <- halves; (name, ft) <- fieldMembers(h) do
                acc.get(name) match
                    case Some(existing) if existing =:= ft => () // dedupe same-name same-type
                    case Some(existing) =>
                        report.errorAndAbort(
                            s"Schema.derived for intersection: field '$name' has conflicting types " +
                                s"${existing.show} vs ${ft.show} across intersected halves."
                        )
                    case None => acc.put(name, ft)
            end for
            acc.toList
        end merged

        if merged.isEmpty then
            report.errorAndAbort(s"Schema.derived: intersection has no fields to encode after deduplication.")

        // Per-field summoned Schema[ft] (widened to Schema[Any] for the runtime loop).
        val fieldSchemaExprs: List[Expr[Schema[Any]]] = merged.map { (name, ft) =>
            ft.asType match
                case '[ft0] =>
                    val s = Expr.summon[Schema[ft0]].getOrElse(
                        report.errorAndAbort(
                            s"No given Schema[${ft.show}] for intersection field '$name'."
                        )
                    )
                    '{ $s.asInstanceOf[Schema[Any]] }
        }

        val fieldNames: List[String] = merged.map(_._1)
        val fieldBytesExprs: List[Expr[Array[Byte]]] = fieldNames.map { n =>
            Expr(n.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
        val fieldBytesArrExpr: Expr[Array[Array[Byte]]] =
            '{ Array[Array[Byte]](${ Varargs(fieldBytesExprs) }*) }
        val fieldSchemasArrExpr: Expr[Array[Schema[Any]]] =
            '{ Array[Schema[Any]](${ Varargs(fieldSchemaExprs) }*) }
        val typeName: String = halves.map(_.typeSymbol.name).mkString("_")
        val nFields: Int     = merged.size

        // We CANNOT pass the reflect.* lists (halves / merged) across into the lambda splices because each splice
        // opens a fresh Quotes scope whose path-dependent reflect.* types do not match the outer ones. Instead the
        // inner splices rebuild halves/merged from `Type[T]` (re-running flattenAnd + dedup, which is pure and
        // stable for the same T). The `fieldNames` / `typeName` / `nFields` ints/strings are scope-free.
        val fieldNamesCapt: List[String] = fieldNames
        val tagExpr: Expr[Tag[T]]        = Expr.summon[Tag[T]].getOrElse('{ kyo.Tag[Any].asInstanceOf[Tag[T]] })
        '{
            val _fieldBytes: Array[Array[Byte]]   = $fieldBytesArrExpr
            val _fieldSchemas: Array[Schema[Any]] = $fieldSchemasArrExpr
            Schema.init[T](
                writeFn = (value: T, writer: Writer) =>
                    ${
                        writeBody[T](
                            'value,
                            'writer,
                            '{ _fieldBytes },
                            '{ _fieldSchemas },
                            fieldNamesCapt,
                            typeName,
                            nFields
                        )
                    },
                readFn = (reader: Reader) =>
                    ${ readBody[T]('reader, '{ _fieldBytes }, '{ _fieldSchemas }, nFields) }
            )(using $tagExpr)
        }
    end deriveSynthesized

    /** Write body: for each merged field, emit `writer.fieldBytes(...)` + `SchemaSerializer.writeTo(...)`. The value is widened via
      * `Select.unique` so the field access resolves against whichever half declares the member.
      */
    private def writeBody[T: Type](using
        Quotes
    )(
        value: Expr[T],
        writer: Expr[Writer],
        fieldBytes: Expr[Array[Array[Byte]]],
        fieldSchemas: Expr[Array[Schema[Any]]],
        fieldNames: List[String],
        typeName: String,
        nFields: Int
    ): Expr[Unit] =
        import quotes.reflect.*
        val typeNameExpr = Expr(typeName)
        val nExpr        = Expr(nFields)
        val writeStmts: List[Expr[Unit]] = fieldNames.zipWithIndex.flatMap { (name, idx) =>
            val idxExpr                = Expr(idx)
            val fieldAccess: Expr[Any] = Select.unique(value.asTerm, name).asExprOf[Any]
            List(
                '{ $writer.fieldBytes($fieldBytes($idxExpr), 0) },
                '{ kyo.internal.SchemaSerializer.writeTo($fieldSchemas($idxExpr), $fieldAccess, $writer)(using kyo.Frame.internal) }
            )
        }
        val bodyExpr: Expr[Unit] =
            if writeStmts.isEmpty then '{ () } else Expr.block(writeStmts.init, writeStmts.last)
        '{
            $writer.objectStart($typeNameExpr, $nExpr)
            $bodyExpr
            $writer.objectEnd()
        }
    end writeBody

    /** Read body: decode each field value into a slot of an `Array[AnyRef]`, then synthesize an anonymous class via `Symbol.newClass` whose
      * vals are populated from the slots. Rebuilds halves / merged from `Type[T]` because reflect.* types from the outer Quotes scope
      * cannot be carried across into this splice's own scope.
      */
    private def readBody[T: Type](using
        Quotes
    )(
        reader: Expr[Reader],
        fieldBytes: Expr[Array[Array[Byte]]],
        fieldSchemas: Expr[Array[Schema[Any]]],
        nFields: Int
    ): Expr[T] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val nExpr                      = Expr(nFields)

        // Rebuild halves and merged from T directly — this stays inside the current Quotes scope.
        val halves: List[TypeRepr] = flattenAnd(TypeRepr.of[T].dealias)
        val merged: List[(String, TypeRepr)] =
            val acc = scala.collection.mutable.LinkedHashMap.empty[String, TypeRepr]
            for h <- halves; (name, ft) <- fieldMembers(h) do
                if !acc.contains(name) then kyo.discard(acc.put(name, ft))
            acc.toList
        end merged

        val parents: List[TypeRepr] = TypeRepr.of[Object] :: halves
        val parentTrees: List[Tree] = parents.map(p => TypeTree.of(using p.asType.asInstanceOf[Type[Any]]))

        val clsSym: Symbol = Symbol.newClass(
            Symbol.spliceOwner,
            "$anonIntersection",
            parents,
            (cls: Symbol) =>
                merged.map { case (name, ft) =>
                    Symbol.newVal(cls, name, ft, Flags.Override, Symbol.noSymbol)
                },
            None
        )

        '{
            val _decoded: Array[AnyRef] = new Array[AnyRef]($nExpr)
            val _                       = $reader.objectStart()
            while $reader.hasNextField() do
                $reader.fieldParse()
                var i       = 0
                var matched = false
                val nLen    = _decoded.length
                while !matched && i < nLen do
                    if $reader.matchField($fieldBytes(i)) then
                        _decoded(i) = kyo.internal.SchemaSerializer
                            .readFrom($fieldSchemas(i), $reader)(using $reader.frame).asInstanceOf[AnyRef]
                        matched = true
                    end if
                    i += 1
                end while
                if !matched then $reader.skip()
            end while
            $reader.objectEnd()
            ${
                val decodedRef: Expr[Array[AnyRef]] = '_decoded
                val clsDecls: List[Symbol]          = clsSym.declaredFields
                val valDefs: List[Definition] = clsDecls.zipWithIndex.map { (s, idx) =>
                    val idxExpr = Expr(idx)
                    val ft      = merged(idx)._2
                    val initExpr: Expr[Any] = ft.asType match
                        case '[ft0] => '{ $decodedRef($idxExpr).asInstanceOf[ft0] }
                    ValDef(s, Some(initExpr.asTerm))
                }
                val classDef = ClassDef(clsSym, parentTrees, valDefs)
                val ctorCall = Apply(Select(New(TypeIdent(clsSym)), clsSym.primaryConstructor), Nil)
                val ascribed = TypeApply(Select.unique(ctorCall, "asInstanceOf"), List(TypeTree.of[T]))
                Block(List(classDef), ascribed).asExprOf[T]
            }
        }
    end readBody

end IntersectionMacro

/** Trampoline proxy to defer loading [[IntersectionMacro]] until `Schema.scala`'s own compilation has settled. Mirrors `UnionMacroProxy`. */
object IntersectionMacroProxy:
    def derive[T: scala.quoted.Type](using scala.quoted.Quotes): scala.quoted.Expr[kyo.Schema[T]] =
        IntersectionMacro.derive[T]
end IntersectionMacroProxy
