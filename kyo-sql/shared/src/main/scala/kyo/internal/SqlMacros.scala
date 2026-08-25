package kyo.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Sql.BoundValue
import kyo.SqlNaming
import scala.quoted.*

/** Compile-time helpers for the Sql DSL entry points.
  *
  * SQL naming is sourced from three channels, in precedence order: the per-field `@column` annotation on the case-class field
  * symbols, an in-scope [[SqlNaming]] casing given, and the verbatim Scala name. The table name is the literal table-name parameter, its
  * default the lowercased type name. All three read at macro time, so column names fold statically wherever the casing given is statically
  * resolvable.
  */
object SqlMacros:

    /** Produces the SQL table name for case-class type `T`.
      *
      * The in-scope [[SqlNaming]] casing governs the table name exactly as it governs column names: `SnakeCase` turns `UserProfile` into
      * `user_profile`. With no casing in scope the default is the lowercased simple type name, and the explicit table-name parameter on
      * the query methods bypasses both. When the casing given is statically resolvable the name folds to a literal; a present but not
      * statically resolvable given applies at runtime, never a wrong un-cased fold.
      */
    /** Compile-time guard behind the bare `Sql.from[T]`: refuses a shape the derived-alias spelling cannot serve, with the explicit
      * alias as the pointed fix.
      *
      * Unit-typed on purpose: macros do not expand while an enclosing `inline def` is being typed, and a deferred `Unit` costs the
      * typing of that definition nothing, where the alias derivation itself must stay a match type ([[kyo.SqlNaming.Decapitalize]])
      * to reduce there. The checks are the type name against PostgreSQL's 63-byte identifier limit, where the server would truncate
      * the alias silently (decapitalization is byte-length-preserving, so the raw name's length is the alias's), and the case-class
      * shape the column staging needs.
      */
    inline def validateDerivedAlias[T]: Unit = ${ validateDerivedAliasImpl[T] }

    def validateDerivedAliasImpl[T: Type](using Quotes): Expr[Unit] =
        import quotes.reflect.*
        val tRepr = TypeRepr.of[T]
        if tRepr.typeSymbol.caseFields.isEmpty then
            report.errorAndAbort(
                "Sql.from[T] derives its alias and columns from a case class with at least one field. " +
                    "For other row shapes, supply an explicit alias: Sql.from[T](\"t\").",
                Position.ofMacroExpansion
            )
        end if
        val name = tRepr.typeSymbol.name
        if name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63 then
            report.errorAndAbort(
                s"the derived alias '${SqlNaming.decapitalize(name)}' exceeds PostgreSQL's 63-byte identifier limit and the server " +
                    "would truncate it silently. Supply an explicit alias: Sql.from[T](\"t\").",
                Position.ofMacroExpansion
            )
        end if
        '{ () }
    end validateDerivedAliasImpl

    inline def tableName[T]: String = ${ tableNameImpl[T] }

    def tableNameImpl[T: Type](using Quotes): Expr[String] =
        val scalaName                     = quotes.reflect.TypeRepr.of[T].typeSymbol.name
        val (namingRuntime, namingStatic) = sqlNaming
        namingStatic match
            case Some(naming) =>
                // Per-char lowercasing for the no-naming default (both branches): String.toLowerCase uses the default
                // locale, which would bake a Turkish dotless i into the derived table name on a tr/az-locale build JVM.
                naming.fold(Expr(scalaName.map(_.toLower)))(n => Expr(n.tableName(scalaName)))
            case None =>
                '{
                    $namingRuntime match
                        case Maybe.Present(n) => n.tableName(${ Expr(scalaName) })
                        case Maybe.Absent     => ${ Expr(scalaName.map(_.toLower)) }
                }
        end match
    end tableNameImpl

    /** Produces the INSERT column names for case-class type `T` as a `Chunk[String]`, in declaration order.
      *
      * Each column resolves through [[resolveColumnName]]: an explicit `@column` rename wins, then the in-scope [[SqlNaming]] casing, then the
      * verbatim field name. When the casing given is statically resolvable the whole list folds to string literals; when it is present but
      * not resolvable (a locally declared given), the rename stays folded while the casing applies at runtime, never a wrong un-cased fold.
      */
    inline def columnNames[T]: Chunk[String] = ${ columnNamesImpl[T] }

    /** The `@column` pairs for `T` as a runtime chunk, for the dynamic render path's name resolution. */
    inline def sqlRenames[T]: Chunk[(String, String)] = ${ sqlRenamesImpl[T] }

    /** Produces `T`'s SQL field names for the [[kyo.SqlSchema]] derivation, in declaration order: the Scala field name, or the
      * `@column` name where one is declared. The query-scoped [[SqlNaming]] casing is deliberately NOT applied here: an
      * `SqlSchema` instance is scope-independent, and casing is applied where the query site's scope is known (rendering and the decode
      * field matcher).
      */
    inline def sqlFieldNames[T]: Seq[String] = ${ sqlFieldNamesImpl[T] }

    def columnNamesImpl[T: Type](using Quotes): Expr[Chunk[String]] =
        import quotes.reflect.*
        val fieldNames                    = TypeRepr.of[T].typeSymbol.caseFields.map(_.name)
        val (namingRuntime, namingStatic) = sqlNaming
        val renames                       = fieldRenames[T]
        namingStatic match
            case Some(naming) =>
                val resolvedNames = fieldNames.map(fn => resolveColumnName(fn, renameOf(renames, fn), naming))
                '{ Chunk(${ Varargs(resolvedNames.map(Expr(_))) }*) }
            case None =>
                // The casing given is present but not statically resolvable: the rename stays a compile-time literal while the casing
                // applies at runtime to the non-renamed fields.
                val fieldExprs = fieldNames.map { fn =>
                    renameOf(renames, fn) match
                        case Maybe.Present(w) => Expr(w)
                        case Maybe.Absent =>
                            '{
                                $namingRuntime match
                                    case Maybe.Present(n) => n.columnName(${ Expr(fn) })
                                    case Maybe.Absent     => ${ Expr(fn) }
                            }
                }
                '{ Chunk(${ Varargs(fieldExprs) }*) }
        end match
    end columnNamesImpl

    /** Produces the auto-increment primary-key column name for case-class type `T` as a `Maybe[String]`, resolved at macro expansion. The
      * "first-column-if-Long" rule: when `T`'s first declared field is `Long`-typed, that field is the auto-key; otherwise `Maybe.empty`.
      *
      * The name resolves through the same `@column` + [[SqlNaming]] path as [[columnNames]], because the renderer emits it as an identifier
      * in `RETURNING` alongside the column list.
      */
    inline def autoKey[T]: Maybe[String] = ${ autoKeyImpl[T] }

    def autoKeyImpl[T: Type](using Quotes): Expr[Maybe[String]] =
        import quotes.reflect.*
        val fields = TypeRepr.of[T].typeSymbol.caseFields
        val isAuto =
            fields.nonEmpty && (TypeRepr.of[T].memberType(fields.head) =:= TypeRepr.of[Long])
        if !isAuto then '{ Maybe.empty[String] }
        else
            val scalaName                     = fields.head.name
            val (namingRuntime, namingStatic) = sqlNaming
            val rename                        = renameOf(fieldRenames[T], scalaName)
            namingStatic match
                case Some(naming) => '{ Maybe(${ Expr(resolveColumnName(scalaName, rename, naming)) }) }
                case None =>
                    rename match
                        case Maybe.Present(w) => '{ Maybe(${ Expr(w) }) }
                        case Maybe.Absent =>
                            '{
                                Maybe($namingRuntime match
                                    case Maybe.Present(n) => n.columnName(${ Expr(scalaName) })
                                    case Maybe.Absent     => ${ Expr(scalaName) })
                            }
            end match
        end if
    end autoKeyImpl

    /** Names the concrete Scala type `A`, captured at an inline construction site and lifted as a string literal, for the diagnostics an AST
      * bind node raises where no runtime tag exists (a `Schema` carries none).
      */
    inline def typeNameOf[A]: String = ${ typeNameImpl[A] }

    def typeNameImpl[A: Type](using Quotes): Expr[String] =
        Expr(Type.show[A])

    /** Decomposes INSERT / VALUES rows of case-class type `T` into pure primitive data: one `Chunk[BoundValue[?]]` per row, each cell a
      * `BoundValue` pairing a field value with its `Schema` and the field type's name, in case-class declaration order.
      *
      * Storing the rows in this decomposed form (rather than as raw `T` instances) keeps the `Insert.Values` / `ValuesFrom` AST nodes pure
      * data, `Chunk`, `BoundValue`, `Schema` all lift via `FromExpr`, so `FromExpr.derived` reconstructs them with zero reflection.
      *
      * Each field's `Schema` is summoned at macro expansion via `Expr.summon`; field access is the case-field selection `<row>.<fieldName>`.
      * Field/column order matches `columnNames[T]` (both walk `caseFields` in declaration order).
      */
    inline def rowValues[T](inline rows: Seq[T]): Chunk[Chunk[BoundValue[?]]] = ${ rowValuesImpl[T]('rows) }

    def rowValuesImpl[T: Type](rows: Expr[Seq[T]])(using Quotes): Expr[Chunk[Chunk[BoundValue[?]]]] =
        import quotes.reflect.*
        val rowExprs: Seq[Expr[T]] = rows match
            case Varargs(es) => es
            case _ =>
                report.errorAndAbort("rowValues requires a literal sequence of rows (varargs).")
        val caseFields = TypeRepr.of[T].typeSymbol.caseFields
        val rowChunks: Seq[Expr[Chunk[BoundValue[?]]]] = rowExprs.map: rowExpr =>
            val rowTerm = rowExpr.asTerm
            val cells: List[Expr[BoundValue[?]]] = caseFields.map: field =>
                val fieldType = TypeRepr.of[T].memberType(field)
                fieldType.asType match
                    case '[ft] =>
                        val fieldValue = Select.unique(rowTerm, field.name).asExprOf[ft]
                        Expr.summon[kyo.SqlSchema.Column[ft]] match
                            case Some(ev) =>
                                '{ BoundValue[ft]($fieldValue, $ev, ${ Expr(Type.show[ft]) }): BoundValue[?] }
                            case None =>
                                report.errorAndAbort(
                                    s"Field '${field.name}' of type ${Type.show[ft]} is not a single-column SQL type for an INSERT/VALUES " +
                                        s"cell. Its type needs a SqlSchema.Column (a supported scalar, Maybe/Option of one, or an installed " +
                                        s"Sql.jsonColumn / Sql.enumText / SqlSchema.of)."
                                )
                        end match
                end match
            // Emit `Chunk(cell*)` (varargs), not `Chunk.from(List(...))`. `FromExpr.derived`'s Chunk matcher
            // recognises the `Chunk.apply` / `Chunk.from` varargs `Repeated` shape; a `List.apply` argument is
            // not lifted, so the decomposed `Insert.Values` would otherwise fail to lift.
            '{ Chunk(${ Varargs(cells) }*) }
        '{ Chunk(${ Varargs(rowChunks.toList) }*) }
    end rowValuesImpl

    /** Resolves the SQL column name for `scalaName`: an explicit `@column` rename wins, then the in-scope [[SqlNaming]] casing, then the field name
      * itself.
      *
      * Shared by [[columnNamesImpl]] and [[autoKeyImpl]] so the emitted column list and the auto-key column can never name the same field
      * two different ways.
      */
    private def resolveColumnName(scalaName: String, rename: Maybe[String], naming: Maybe[SqlNaming]): String =
        rename match
            case Maybe.Present(w) => w
            case Maybe.Absent =>
                naming match
                    case Maybe.Present(n) => n.columnName(scalaName)
                    case Maybe.Absent     => scalaName

    /** The `@column` names declared on `T`'s case-class fields, as a `scalaFieldName -> columnName` map, read at macro time from
      * the primary-constructor parameter symbols. Empty when no field carries the annotation.
      */
    private def fieldRenames[T: Type](using Quotes): Map[String, String] =
        renameMapOf(quotes.reflect.TypeRepr.of[T])

    /** The `@column` pairs of `tpe`'s case-class fields, read from the primary-constructor parameter symbols. Shared with the
      * static-lift reconstruction ([[kyo.internal.macros.RecordFromExpr]]), which re-reads them from the type a lifted
      * `SqlNameResolver.columnName[T]` call names, so the folded name and the runtime name can never diverge.
      */
    private[kyo] def renameMapOf(using q: Quotes)(tpe: q.reflect.TypeRepr): Map[String, String] =
        import q.reflect.*
        def firstStringArg(term: Term): Option[String] = term match
            case Apply(_, args) =>
                args.collectFirst {
                    case Literal(StringConstant(s))              => s
                    case NamedArg(_, Literal(StringConstant(s))) => s
                    case Typed(Literal(StringConstant(s)), _)    => s
                }
            case _ => None
        tpe.typeSymbol.primaryConstructor.paramSymss.flatten.flatMap { p =>
            p.annotations.reverse
                .find(_.tpe <:< TypeRepr.of[kyo.column])
                .flatMap(firstStringArg)
                .map(p.name -> _)
        }.toMap
    end renameMapOf

    private def sqlRenamesImpl[T: Type](using Quotes): Expr[Chunk[(String, String)]] =
        val pairs: Seq[(String, String)] = fieldRenames[T].toSeq
        '{ Chunk.from(${ Expr(pairs) }) }

    private def sqlFieldNamesImpl[T: Type](using Quotes): Expr[Seq[String]] =
        import quotes.reflect.*
        val renames = fieldRenames[T]
        val names = TypeRepr.of[T].typeSymbol.caseFields.map { f =>
            renames.getOrElse(f.name, f.name)
        }
        Expr(names)
    end sqlFieldNamesImpl

    /** The `@column` wire name for `scalaName` in `renames`, as a `Maybe`. */
    private def renameOf(renames: Map[String, String], scalaName: String): Maybe[String] =
        renames.get(scalaName) match
            case Some(w) => Maybe(w)
            case None    => Maybe.empty

    /** Summons the in-scope [[SqlNaming]] casing given at macro time, returning both a runtime `Maybe[SqlNaming]` (for the fallback path) and
      * the statically resolved value (for the constant-fold path).
      *
      * The static value is `Some(Maybe.empty)` when no given is in scope (fold as the `Identity` default), `Some(Maybe(n))` when a given is
      * present and statically resolvable via [[FromExprDerived.resolveStableGiven]] (fold with `n`), and `None` when a given is present but
      * NOT statically resolvable (a locally declared given): the caller must then emit the runtime path rather than silently fold `Identity`,
      * which would render wrong SQL.
      */
    private def sqlNaming(using Quotes): (Expr[Maybe[SqlNaming]], Option[Maybe[SqlNaming]]) =
        Expr.summon[SqlNaming] match
            case None => ('{ Maybe.empty[SqlNaming] }, Some(Maybe.empty))
            case Some(e) =>
                val static = kyo.internal.FromExprDerived.resolveStableGiven[SqlNaming](e).map(n => Maybe(n))
                ('{ Maybe($e) }, static)
    end sqlNaming

end SqlMacros
