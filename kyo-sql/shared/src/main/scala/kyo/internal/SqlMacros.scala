package kyo.internal

import kyo.BoundValue
import kyo.Chunk
import kyo.Maybe
import kyo.SqlSchema
import scala.quoted.*

/** Compile-time helpers for the Sql DSL entry points. */
object SqlMacros:

    /** Produces the SQL table name for case-class type `T` as a `Literal(StringConstant(...))` in the call-site tree.
      *
      * Resolution order:
      *   1. `tableNameOverride` from a `given SqlSchema[T]` in scope (set via `.withTableName(...)`).
      *   2. `namingStrategy.tableName(simpleName)` from a `given SqlSchema[T]` in scope.
      *   3. Fallback: `T.typeSymbol.name.toLowerCase` (byte-for-byte identical to the pre-change behaviour).
      *
      * The `given SqlSchema[T]` is summoned at macro-expansion time; if none is in scope, the fallback applies.
      */
    inline def tableName[T]: String = ${ tableNameImpl[T] }

    def tableNameImpl[T: Type](using Quotes): Expr[String] =
        val scalaName = quotes.reflect.TypeRepr.of[T].typeSymbol.name
        Expr.summon[SqlSchema[T]] match
            case Some(schemaExpr) =>
                // Try to extract override info from the schema's construction expression. If extractable,
                // constant-fold the resolved name here so the static-SQL macro can lift it via FromExpr.derived.
                SqlSchemaInfo.extract(schemaExpr) match
                    case Some(info) =>
                        val resolved = info.tableNameOverride match
                            case Maybe.Present(name) => name
                            case Maybe.Absent =>
                                info.namingStrategy match
                                    case Maybe.Present(strategy) => strategy.tableName(scalaName)
                                    case Maybe.Absent            => scalaName.toLowerCase
                        Expr(resolved)
                    case None =>
                        // Fallback: emit runtime resolution. Loses the constant-fold for the static path
                        // when the schema's construction isn't statically recognisable; the runtime render
                        // path still resolves the name correctly.
                        '{ kyo.internal.SqlNameResolver.tableName(${ Expr(scalaName) }, $schemaExpr) }
                end match
            case None =>
                Expr(scalaName.toLowerCase)
        end match
    end tableNameImpl

    /** Produces the INSERT column names for case-class type `T` as a `Chunk[String]`, in declaration order.
      *
      * When a `given SqlSchema[T]` is in scope, the macro tries to extract its override info via [[SqlSchemaInfo]] and constant-fold the
      * resolved names. If the schema's construction is not statically recognisable, the macro falls back to emitting runtime calls to
      * [[SqlNameResolver.columnName]] (the runtime path still resolves correctly; the static-SQL macro lift is lost for that call site).
      *
      * Without a schema in scope, the macro emits the verbatim Scala field names as string literals.
      */
    inline def columnNames[T]: Chunk[String] = ${ columnNamesImpl[T] }

    def columnNamesImpl[T: Type](using Quotes): Expr[Chunk[String]] =
        import quotes.reflect.*
        val fieldNames = TypeRepr.of[T].typeSymbol.caseFields.map(_.name)

        def resolveWith(info: SqlSchemaInfo.Info, scalaName: String): String =
            info.renamedFields.find(_._1 == scalaName) match
                case Some((_, sqlName)) => sqlName
                case None =>
                    info.namingStrategy match
                        case Maybe.Present(strategy) => strategy.columnName(scalaName)
                        case Maybe.Absent            => scalaName

        Expr.summon[SqlSchema[T]] match
            case Some(schemaExpr) =>
                SqlSchemaInfo.extract(schemaExpr) match
                    case Some(info) =>
                        // Constant-fold each field name; emit string-literal varargs liftable by FromExpr.derived.
                        val resolvedNames = fieldNames.map(resolveWith(info, _))
                        '{ Chunk(${ Varargs(resolvedNames.map(Expr(_))) }*) }
                    case None =>
                        // Fallback: emit runtime resolution per field.
                        val fieldExprs = fieldNames.map { name =>
                            '{ kyo.internal.SqlNameResolver.columnName(${ Expr(name) }, $schemaExpr) }
                        }
                        '{ Chunk(${ Varargs(fieldExprs) }*) }
                end match
            case None =>
                '{ Chunk(${ Varargs(fieldNames.map(Expr(_))) }*) }
        end match
    end columnNamesImpl

    /** Produces the auto-increment primary-key column name for case-class type `T` as a `Maybe[String]` of constant data, resolved at macro
      * expansion. The Phase-5 "first-column-if-Long" rule: when `T`'s first declared field is `Long`-typed, that field is the auto-key;
      * otherwise `Maybe.empty`.
      *
      * Computing this at macro expansion (rather than via a runtime `if` on `Fields[T]`) keeps `Insert.autoKey` a pure constructor of
      * constant data — `FromExpr.derived` lifts `Maybe(<lit>)` / `Maybe.empty`, but cannot lift an `If` tree.
      */
    inline def autoKey[T]: Maybe[String] = ${ autoKeyImpl[T] }

    def autoKeyImpl[T: Type](using Quotes): Expr[Maybe[String]] =
        import quotes.reflect.*
        val fields = TypeRepr.of[T].typeSymbol.caseFields
        val isAuto =
            fields.nonEmpty && (TypeRepr.of[T].memberType(fields.head) =:= TypeRepr.of[Long])
        if isAuto then '{ Maybe(${ Expr(fields.head.name) }) } else '{ Maybe.empty[String] }
    end autoKeyImpl

    /** Decomposes INSERT / VALUES rows of case-class type `T` into pure primitive data: one `Chunk[BoundValue[?]]` per row, each cell a
      * `BoundValue` pairing a field value with its `SqlSchema`, in case-class declaration order.
      *
      * Storing the rows in this decomposed form (rather than as raw `T` instances) keeps the `Insert.Values` / `ValuesFrom` AST nodes pure
      * data — `Chunk`, `BoundValue`, `SqlSchema` all lift via `FromExpr`, so `FromExpr.derived` reconstructs them with zero reflection. A
      * raw `Chunk[T]` would force `FromExprDerived.instantiate` to reflectively `Class.forName` a row class co-compiled with the
      * `staticSql` call site, which does not exist at macro-expansion time.
      *
      * Each field's `SqlSchema` is summoned at macro expansion via `Expr.summon`; field access is the case-field selection
      * `<row>.<fieldName>`. Field/column order matches `columnNames[T]` (both walk `caseFields` in declaration order).
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
                        Expr.summon[SqlSchema[ft]] match
                            case Some(schema) =>
                                '{ BoundValue[ft]($fieldValue, $schema): BoundValue[?] }
                            case None =>
                                report.errorAndAbort(
                                    s"No SqlSchema found for field '${field.name}' of type ${Type.show[ft]} in INSERT/VALUES row."
                                )
                        end match
                end match
            // Emit `Chunk(cell*)` (varargs) — not `Chunk.from(List(...))`. `FromExpr.derived`'s Chunk matcher
            // recognises the `Chunk.apply` / `Chunk.from` varargs `Repeated` shape; a `List.apply` argument is
            // not lifted, so the decomposed `Insert.Values` would otherwise fail to lift.
            '{ Chunk(${ Varargs(cells) }*) }
        '{ Chunk(${ Varargs(rowChunks.toList) }*) }
    end rowValuesImpl

end SqlMacros
