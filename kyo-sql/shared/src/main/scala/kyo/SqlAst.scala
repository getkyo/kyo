package kyo

import kyo.Record.~
import kyo.SqlSchema.BoundValue
import kyo.internal.dsl.WindowSpecBuilder
import scala.annotation.targetName
import scala.compiletime.summonFrom
import scala.deriving.Mirror

/** SQL Abstract Syntax Tree for the Sql DSL.
  *
  * Holds every AST node, every typeclass, and every DSL method that constructs an AST node. The companion `Sql` object exposes top-level
  * entry points (`from`, `insert`, `when`, etc.) that delegate here. DSL builders (e.g. [[kyo.internal.dsl.WindowSpecBuilder]]) live in
  * `kyo.internal.dsl` and are re-exported from the `Sql` companion for user-facing access.
  *
  * Users typically `import kyo.SqlAst.*` to bring all types and the DSL methods on `Term` / `Column` / `Query` / etc. into scope.
  *
  * Design, no `Kind` parameter; aggregate / window distinctions are encoded in the type structure:
  *
  *   - [[Term]], any expression usable as a scalar.
  *   - [[Column]], table column reference. Aggregate methods (`.sum`, `.count`, `.avg`, …) produce a [[WindowAggregate]] (NOT a `Term`),
  *     usable only via `.over(spec)`. This blocks `where(col.count > 5)` and `col.count.sum` at compile time.
  *   - [[GroupTerm]], post-groupBy ungrouped column. Only aggregate methods, all returning `Term[V]`.
  *   - [[GroupedColumn]], post-groupBy grouped column. Has the `Term[V]` surface AND aggregate methods returning `Term[V]`.
  *   - Whole-table aggregates without GROUP BY use [[From.sum]] / [[From.count]] / etc., returning a `Query[V]` (a `Term[V]` via scalar
  *     subquery).
  */
object SqlAst:

    // Re-export the window-spec builder so `import kyo.SqlAst.*` keeps working.
    export kyo.internal.dsl.WindowSpecBuilder

    // --- SqlAst, unified root ---

    /** Root of the SQL AST hierarchy. Every node, expressions, queries, mutations, raw fragments, is a `SqlAst[A]`. */
    sealed trait SqlAst[A]:

        /** Renders this AST node into a [[Sql.Rendered]] using PostgreSQL syntax (`$N` placeholders, `"…"` identifiers). Use for
          * debugging, logging, or migration scripts without a live client. When a client is already in hand, prefer [[SqlClient.render]],
          * which picks the client's own backend.
          */
        final def renderPostgres(using frame: Frame): Sql.Rendered =
            val r = kyo.internal.SqlRender.render(this, kyo.internal.SqlBackend.Postgres, frame)
            Sql.Rendered(r.sql, r.params)

        /** Renders this AST node into a [[Sql.Rendered]] using MySQL syntax (`?` placeholders, `` `…` `` identifiers), assuming the
          * latest broadly-supported MySQL version (8.4.0). Use for debugging, logging, or migration scripts without a live client. When a
          * client is already in hand, prefer [[SqlClient.render]], which picks the client's own backend.
          */
        final def renderMysql(using frame: Frame): Sql.Rendered =
            val r = kyo.internal.SqlRender.render(this, kyo.internal.SqlBackend.Mysql, frame)
            Sql.Rendered(r.sql, r.params)
    end SqlAst

    // --- Executable, marker for AST nodes that can be run via SqlClient ---

    /** Marker trait for AST nodes that can be executed via [[kyo.SqlClient]]: [[Query]] (DQL), [[Action]] (DML), and [[Fragment]] (raw
      * SQL).
      */
    sealed trait Executable[A] extends SqlAst[A]

    // --- Term, the universal expression interface ---

    /** Universal SQL expression interface. Every DSL value, column references, literals, function applications, and boolean combinators,
      * is a `Term[A]`.
      *
      * Symbolic operators (`==`, `!=`, `<`, `<=`, `>`, `>=`, `+`, `-`, `*`, `/`, `%`, `&&`, `||`, `unary_!`, `++`) are defined on `Term` by
      * design. CONTRIBUTING.md §195 restricts symbolic operators to `kyo-data`, `kyo-prelude`, and `kyo-core`; kyo-sql is not named in that
      * list. A SQL expression DSL is a categorically different context: the operators mirror the SQL they compile to (`col >= 18 &&
      * col.name != ""`), and every operator maps 1-to-1 to a SQL construct with no natural named-method equivalent. This exemption is
      * deliberate and documented here per the maintainer decision recorded in the audit.
      */
    sealed trait Term[A] extends SqlAst[A]:

        // -- Comparison ------------------------------------------------------

        final inline def ==(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, other)

        @targetName("eqRaw")
        final inline def ==(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, Literal(other, s))

        final inline def !=(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, other)

        @targetName("neqRaw")
        final inline def !=(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, Literal(other, s))

        final inline def <(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, other)

        @targetName("ltRaw")
        final inline def <(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, Literal(other, s))

        final inline def <=(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, other)

        @targetName("lteRaw")
        final inline def <=(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, Literal(other, s))

        final inline def >(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, other)

        @targetName("gtRaw")
        final inline def >(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, Literal(other, s))

        final inline def >=(inline other: Term[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, other)

        @targetName("gteRaw")
        final inline def >=(inline other: A)(using s: SqlSchema[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, Literal(other, s))

        // -- Ordering specs --------------------------------------------------
        final inline def asc: OrderSpec            = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.Nulls.Default)
        final inline def ascNullsFirst: OrderSpec  = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.Nulls.First)
        final inline def ascNullsLast: OrderSpec   = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.Nulls.Last)
        final inline def desc: OrderSpec           = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.Nulls.Default)
        final inline def descNullsFirst: OrderSpec = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.Nulls.First)
        final inline def descNullsLast: OrderSpec  = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.Nulls.Last)

        // -- Membership ------------------------------------------------------
        final inline def in(inline values: Term[A]*): Term[Boolean] = InValues(this, Chunk.from(values))

        @targetName("inRaw")
        final inline def in(inline values: A*)(using s: SqlSchema[A]): Term[Boolean] =
            InValues(this, Chunk.from(values).map(v => Literal(v, s)))

        final inline def in(inline query: Query[A]): Term[Boolean] = InSubquery(this, query)

        final inline def notIn(inline values: Term[A]*): Term[Boolean] = NotInValues(this, Chunk.from(values))

        @targetName("notInRaw")
        final inline def notIn(inline values: A*)(using s: SqlSchema[A]): Term[Boolean] =
            NotInValues(this, Chunk.from(values).map(v => Literal(v, s)))

        final inline def notIn(inline query: Query[A]): Term[Boolean] = NotInSubquery(this, query)

        // -- Null handling ---------------------------------------------------
        final def isNull(using IsNullable[A]): Term[Boolean]    = IsNull(this)
        final def isNotNull(using IsNullable[A]): Term[Boolean] = IsNotNull(this)

        // -- Range -----------------------------------------------------------

        final inline def between(inline low: Term[A], inline high: Term[A]): Term[Boolean] = Between(this, low, high)

        @targetName("betweenRaw")
        final inline def between(inline low: A, inline high: A)(using s: SqlSchema[A]): Term[Boolean] =
            Between(this, Literal(low, s), Literal(high, s))

        // -- Coalesce / nullIf ----------------------------------------------
        final def coalesce(others: Term[A]*): Term[A] = Coalesce(this +: Chunk.from(others))

        @targetName("coalesceRaw")
        final def coalesce(others: A*)(using s: SqlSchema[A]): Term[A] =
            Coalesce(this +: Chunk.from(others).map(v => Literal(v, s)))

        final def nullIf(other: Term[A]): Term[Maybe[A]] = NullIf(this, other)

        @targetName("nullIfRaw")
        final def nullIf(other: A)(using s: SqlSchema[A]): Term[Maybe[A]] =
            NullIf(this, Literal(other, s))

        // -- Cast ------------------------------------------------------------
        final inline def cast[B](using s: SqlSchema[B]): Term[B] = Cast(this, s.sqlTypeName)

        // -- Labelled term ---------------------------------------------------
        final def as[N <: String & Singleton](label: N): LabelledTerm[N, A] = LabelledTerm(label, this)

        // -- Boolean-only ops (gated by A =:= Boolean) ----------------------
        final inline def &&(inline other: Term[Boolean])(using ev: A =:= Boolean): Term[Boolean] =
            Logical(ev.substituteCo[[T] =>> Term[T]](this), Logical.Op.And, other)

        final inline def ||(inline other: Term[Boolean])(using ev: A =:= Boolean): Term[Boolean] =
            Logical(ev.substituteCo[[T] =>> Term[T]](this), Logical.Op.Or, other)

        final inline def unary_!(using ev: A =:= Boolean): Term[Boolean] = Not(ev.substituteCo[[T] =>> Term[T]](this))

        final def isTrue(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsTrue)

        final def isNotTrue(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsNotTrue)

        final def isFalse(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsFalse)

        final def isNotFalse(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsNotFalse)

        // -- Maybe[Boolean]-only ops ----------------------------------------
        final def isUnknown(using ev: A =:= Maybe[Boolean]): Term[Boolean]    = IsUnknown(ev.substituteCo[[T] =>> Term[T]](this))
        final def isNotUnknown(using ev: A =:= Maybe[Boolean]): Term[Boolean] = IsNotUnknown(ev.substituteCo[[T] =>> Term[T]](this))

        // -- Numeric-only ops (gated by SqlNumeric[A]) ----------------------
        final inline def +(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic(this, Arithmetic.Op.Add, other)

        @targetName("plusRaw")
        final inline def +(inline other: A)(using n: SqlNumeric[A], s: SqlSchema[A]): Term[A] =
            Arithmetic(this, Arithmetic.Op.Add, Literal(other, s))

        final inline def -(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic(this, Arithmetic.Op.Sub, other)

        @targetName("minusRaw")
        final inline def -(inline other: A)(using n: SqlNumeric[A], s: SqlSchema[A]): Term[A] =
            Arithmetic(this, Arithmetic.Op.Sub, Literal(other, s))

        final inline def *(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic(this, Arithmetic.Op.Mul, other)

        @targetName("mulRaw")
        final inline def *(inline other: A)(using n: SqlNumeric[A], s: SqlSchema[A]): Term[A] =
            Arithmetic(this, Arithmetic.Op.Mul, Literal(other, s))

        final inline def /(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic(this, Arithmetic.Op.Div, other)

        @targetName("divRaw")
        final inline def /(inline other: A)(using n: SqlNumeric[A], s: SqlSchema[A]): Term[A] =
            Arithmetic(this, Arithmetic.Op.Div, Literal(other, s))

        final inline def %(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic(this, Arithmetic.Op.Mod, other)

        @targetName("modRaw")
        final inline def %(inline other: A)(using n: SqlNumeric[A], s: SqlSchema[A]): Term[A] =
            Arithmetic(this, Arithmetic.Op.Mod, Literal(other, s))

        final def abs(using SqlNumeric[A]): Term[A]     = NumericFn(this, NumericFn.Op.Abs)
        final def ceiling(using SqlNumeric[A]): Term[A] = NumericFn(this, NumericFn.Op.Ceiling)
        final def floor(using SqlNumeric[A]): Term[A]   = NumericFn(this, NumericFn.Op.Floor)
        final def round(using SqlNumeric[A]): Term[A]   = NumericFn(this, NumericFn.Op.Round)

        // -- String-only ops (gated by A =:= String) ------------------------
        // asStr must remain inline so that callers used in staticSql expressions are macro-liftable.
        private inline def asStr(using ev: A =:= String): Term[String]                    = ev.substituteCo[[T] =>> Term[T]](this)
        final def upper(using A =:= String): Term[String]                                 = StringFn(asStr, StringFn.Op.Upper)
        final def lower(using A =:= String): Term[String]                                 = StringFn(asStr, StringFn.Op.Lower)
        final def length(using A =:= String): Term[Int]                                   = StringLength(asStr)
        final def trim(using A =:= String): Term[String]                                  = StringFn(asStr, StringFn.Op.Trim)
        final inline def ++(inline other: Term[String])(using A =:= String): Term[String] = Concat(Chunk(asStr, other))

        @targetName("concatRaw")
        final inline def ++(inline other: String)(using A =:= String): Term[String] =
            Concat(Chunk(asStr, Literal(other, summon[SqlSchema[String]])))

        final inline def like(inline pattern: Term[String])(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.Like, pattern)

        @targetName("likeRaw")
        final inline def like(inline pattern: String)(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.Like, Literal(pattern, summon[SqlSchema[String]]))

        final inline def notLike(inline pattern: Term[String])(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.NotLike, pattern)

        @targetName("notLikeRaw")
        final inline def notLike(inline pattern: String)(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.NotLike, Literal(pattern, summon[SqlSchema[String]]))

        final inline def ilike(inline pattern: Term[String])(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.ILike, pattern)

        @targetName("ilikeRaw")
        final inline def ilike(inline pattern: String)(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.ILike, Literal(pattern, summon[SqlSchema[String]]))

        final inline def notIlike(inline pattern: Term[String])(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.NotILike, pattern)

        @targetName("notIlikeRaw")
        final inline def notIlike(inline pattern: String)(using A =:= String): Term[Boolean] =
            StringMatch(asStr, StringMatch.Op.NotILike, Literal(pattern, summon[SqlSchema[String]]))

        final inline def substring(inline start: Term[Int])(using A =:= String): Term[String] = Substring(asStr, start, Maybe.empty)

        @targetName("substringRaw1")
        final inline def substring(inline start: Int)(using A =:= String): Term[String] =
            Substring(asStr, Literal(start, summon[SqlSchema[Int]]), Maybe.empty)

        final inline def substring(inline start: Term[Int], inline length: Term[Int])(using A =:= String): Term[String] =
            Substring(asStr, start, Maybe(length))

        @targetName("substringRaw2")
        final inline def substring(inline start: Int, inline length: Int)(using A =:= String): Term[String] =
            Substring(asStr, Literal(start, summon[SqlSchema[Int]]), Maybe(Literal(length, summon[SqlSchema[Int]])))
    end Term

    // --- Concrete Term subtypes ---

    /** A column reference carrying both the column's name (singleton type) and value type.
      *
      * @param alias
      *   the table alias this column belongs to (empty string for row-level columns).
      * @param name
      *   the Scala field name (singleton type N encodes it statically).
      * @param sqlName
      *   the SQL-side name to emit in rendered SQL (post-rename, post-naming-strategy). Equals `name` when no naming transforms are
      *   applied.
      */
    final case class Column[N <: String & Singleton, V](alias: String, name: N, sqlName: String) extends Term[V] derives CanEqual:

        inline def :=(inline other: Term[V]): SetSpec[N, V] = SetSpec(this, other)

        @targetName("assignRaw")
        inline def :=(inline other: V)(using s: SqlSchema[V]): SetSpec[N, V] = SetSpec(this, Literal(other, s))

        // Window-only aggregates (return WindowAggregate, NOT a Term)
        inline def sum: WindowAggregate[V]              = WindowAggregate(Aggregate.Sum(this, distinct = false))
        inline def sumDistinct: WindowAggregate[V]      = WindowAggregate(Aggregate.Sum(this, distinct = true))
        inline def avg: WindowAggregate[V]              = WindowAggregate(Aggregate.Avg(this, distinct = false))
        inline def avgDistinct: WindowAggregate[V]      = WindowAggregate(Aggregate.Avg(this, distinct = true))
        inline def min: WindowAggregate[V]              = WindowAggregate(Aggregate.Min(this))
        inline def max: WindowAggregate[V]              = WindowAggregate(Aggregate.Max(this))
        inline def count: WindowAggregate[Long]         = WindowAggregate(Aggregate.Count(Maybe(this), distinct = false))
        inline def countDistinct: WindowAggregate[Long] = WindowAggregate(Aggregate.Count(Maybe(this), distinct = true))

        // Window-function constructors (return WindowFunction; only `.over(spec)` produces a Term)
        inline def lead: WindowFunction[V] = WindowFunction.Lead(this, oneInt, Maybe.empty)

        @targetName("leadInt")
        inline def lead(inline offset: Int): WindowFunction[V] =
            WindowFunction.Lead(this, intLit(offset), Maybe.empty)

        inline def lead(inline offset: Term[Int]): WindowFunction[V] = WindowFunction.Lead(this, offset, Maybe.empty)

        @targetName("leadIntRaw")
        inline def lead(inline offset: Int, inline default: V)(using s: SqlSchema[V]): WindowFunction[V] =
            WindowFunction.Lead(this, intLit(offset), Maybe(Literal(default, s)))

        inline def lead(inline offset: Term[Int], inline default: Term[V]): WindowFunction[V] =
            WindowFunction.Lead(this, offset, Maybe(default))

        inline def lag: WindowFunction[V] = WindowFunction.Lag(this, oneInt, Maybe.empty)

        @targetName("lagInt")
        inline def lag(inline offset: Int): WindowFunction[V] = WindowFunction.Lag(this, intLit(offset), Maybe.empty)

        inline def lag(inline offset: Term[Int]): WindowFunction[V] = WindowFunction.Lag(this, offset, Maybe.empty)

        @targetName("lagIntRaw")
        inline def lag(inline offset: Int, inline default: V)(using s: SqlSchema[V]): WindowFunction[V] =
            WindowFunction.Lag(this, intLit(offset), Maybe(Literal(default, s)))

        inline def firstValue: WindowFunction[V] = WindowFunction.FirstValue(this)

        inline def lastValue: WindowFunction[V] = WindowFunction.LastValue(this)

        @targetName("nthValueInt")
        inline def nthValue(inline n: Int): WindowFunction[V] = WindowFunction.NthValue(this, intLit(n))

        inline def nthValue(inline n: Term[Int]): WindowFunction[V] = WindowFunction.NthValue(this, n)
    end Column

    final case class Literal[A](value: A, schema: SqlSchema[A]) extends Term[A]

    /** A term carrying an explicit label. Created via `term.as("name")`, used by `groupBy` / `select` for labelled projections. The label
      * is preserved by the renderer when it generates `expr AS "name"`; in non-projection contexts (WHERE, etc.) the label is irrelevant
      * and the inner term is rendered.
      */
    final case class LabelledTerm[N <: String & Singleton, A](label: N, term: Term[A]) extends Term[A] derives CanEqual

    final case class Comparison[A](left: Term[A], op: Comparison.Op, right: Term[A]) extends Term[Boolean] derives CanEqual
    object Comparison:
        enum Op derives CanEqual:
            case Eq, NotEq, Lt, Lte, Gt, Gte

    final case class Logical(left: Term[Boolean], op: Logical.Op, right: Term[Boolean]) extends Term[Boolean] derives CanEqual
    object Logical:
        enum Op derives CanEqual:
            case And, Or

    final case class Not(expr: Term[Boolean]) extends Term[Boolean] derives CanEqual

    final case class BoolTest(expr: Term[Boolean], pred: BoolTest.Predicate) extends Term[Boolean] derives CanEqual
    object BoolTest:
        enum Predicate derives CanEqual:
            case IsTrue, IsNotTrue, IsFalse, IsNotFalse

    final case class IsNull[A](expr: Term[A])    extends Term[Boolean] derives CanEqual
    final case class IsNotNull[A](expr: Term[A]) extends Term[Boolean] derives CanEqual

    final case class IsUnknown(expr: Term[Maybe[Boolean]])    extends Term[Boolean] derives CanEqual
    final case class IsNotUnknown(expr: Term[Maybe[Boolean]]) extends Term[Boolean] derives CanEqual

    final case class Between[A](expr: Term[A], low: Term[A], high: Term[A]) extends Term[Boolean] derives CanEqual

    final case class InValues[A](expr: Term[A], values: Chunk[Term[A]])    extends Term[Boolean] derives CanEqual
    final case class NotInValues[A](expr: Term[A], values: Chunk[Term[A]]) extends Term[Boolean] derives CanEqual

    final case class InSubquery[A](expr: Term[A], subquery: Query[A])    extends Term[Boolean] derives CanEqual
    final case class NotInSubquery[A](expr: Term[A], subquery: Query[A]) extends Term[Boolean] derives CanEqual

    final case class Exists(subquery: Query[?])    extends Term[Boolean] derives CanEqual
    final case class NotExists(subquery: Query[?]) extends Term[Boolean] derives CanEqual

    final case class ScalarSub[A](subquery: Query[A]) extends Term[A] derives CanEqual

    final case class Coalesce[A](exprs: Chunk[Term[A]]) extends Term[A] derives CanEqual

    final case class NullIf[A](left: Term[A], right: Term[A]) extends Term[Maybe[A]] derives CanEqual

    final case class Cast[A, B](expr: Term[A], sqlTypeName: String) extends Term[B] derives CanEqual

    final case class Concat(parts: Chunk[Term[String]]) extends Term[String] derives CanEqual

    final case class Arithmetic[A](left: Term[A], op: Arithmetic.Op, right: Term[A]) extends Term[A] derives CanEqual
    object Arithmetic:
        enum Op derives CanEqual:
            case Add, Sub, Mul, Div, Mod

    final case class NumericFn[A](expr: Term[A], op: NumericFn.Op) extends Term[A] derives CanEqual
    object NumericFn:
        enum Op derives CanEqual:
            case Abs, Ceiling, Floor, Round

    final case class StringFn(expr: Term[String], op: StringFn.Op) extends Term[String] derives CanEqual
    object StringFn:
        enum Op derives CanEqual:
            case Upper, Lower, Trim

    final case class StringLength(expr: Term[String]) extends Term[Int] derives CanEqual

    final case class StringMatch(expr: Term[String], op: StringMatch.Op, pattern: Term[String]) extends Term[Boolean] derives CanEqual
    object StringMatch:
        enum Op derives CanEqual:
            case Like, NotLike, ILike, NotILike

    final case class Substring(expr: Term[String], start: Term[Int], length: Maybe[Term[Int]]) extends Term[String] derives CanEqual

    final case class CaseExpr[A](whens: Chunk[(Term[Boolean], Term[A])], otherwise: Term[A]) extends Term[A] derives CanEqual
    final case class CaseExprNullable[A](whens: Chunk[(Term[Boolean], Term[A])])             extends Term[Maybe[A]] derives CanEqual

    final case class Default[A]() extends Term[A] derives CanEqual

    final case class FunctionCall[A](name: String, args: Chunk[Term[?]]) extends Term[A] derives CanEqual

    final case class RawSql[A](sql: String) extends Term[A] derives CanEqual

    /** A composable SQL fragment: a sequence of [[Fragment.Part]]s alternating between literal text, bound values, and embedded [[Term]]s.
      * Built by the `sql"..."` interpolator in `Sql.scala` or by lifting raw values via the companion. Composes via `++`; usable anywhere a
      * `Term[A]` is expected.
      */
    final case class Fragment[A](parts: Chunk[Fragment.Part]) extends Executable[A] with Term[A]:
        /** Concatenate two fragments. The output type widens to the right-hand side (matches Doobie's convention). */
        def ++[B](other: Fragment[B]): Fragment[B] = Fragment[B](parts ++ other.parts)

        /** Re-tag the phantom output type. */
        def as[B]: Fragment[B] = Fragment[B](parts)
    end Fragment

    object Fragment:
        sealed trait Part
        final case class Lit(text: String)                       extends Part derives CanEqual
        final case class Bind[A](value: A, schema: SqlSchema[A]) extends Part
        final case class Embed(term: Term[?])                    extends Part derives CanEqual

        /** An empty fragment, identity for `++`. */
        def empty[A]: Fragment[A] = Fragment[A](Chunk.empty)

        /** A literal-text-only fragment (no binds, no embeds). */
        def lit[A](text: String): Fragment[A] = Fragment[A](Chunk(Lit(text)))
    end Fragment

    final case class Excluded[N <: String & Singleton, V](column: Column[N, V]) extends Term[V] derives CanEqual

    final case class Windowed[A](
        inner: Aggregate.Call[A] | WindowFunction[A],
        spec: WindowSpec
    ) extends Term[A] derives CanEqual

    // --- Aggregates, sealed family of call nodes (Term[A]) ---

    object Aggregate:
        sealed abstract class Call[A]                                   extends Term[A]
        final case class Count(expr: Maybe[Term[?]], distinct: Boolean) extends Call[Long] derives CanEqual
        final case class Sum[A](expr: Term[A], distinct: Boolean)       extends Call[A] derives CanEqual
        final case class Avg[A](expr: Term[A], distinct: Boolean)       extends Call[A] derives CanEqual
        final case class Min[A](expr: Term[A])                          extends Call[A] derives CanEqual
        final case class Max[A](expr: Term[A])                          extends Call[A] derives CanEqual
    end Aggregate

    // --- WindowAggregate, wraps an Aggregate.Call, only usable via .over(spec) ---

    final case class WindowAggregate[A](inner: Aggregate.Call[A]) derives CanEqual:
        inline def over(inline spec: WindowSpec): Term[A]           = Windowed(inner, spec)
        inline def over(inline builder: WindowSpecBuilder): Term[A] = Windowed(inner, builder.build)

    // --- WindowFunction, column-based and standalone window functions ---

    sealed trait WindowFunction[A]:
        inline def over(inline spec: WindowSpec): Term[A]           = Windowed(this, spec)
        inline def over(inline builder: WindowSpecBuilder): Term[A] = Windowed(this, builder.build)

    object WindowFunction:
        given CanEqual[WindowFunction[?], WindowFunction[?]] = CanEqual.derived

        case object RowNumber                                                               extends WindowFunction[Long]
        case object Rank                                                                    extends WindowFunction[Long]
        case object DenseRank                                                               extends WindowFunction[Long]
        case object PercentRank                                                             extends WindowFunction[Double]
        case object CumeDist                                                                extends WindowFunction[Double]
        final case class Ntile(n: Term[Int])                                                extends WindowFunction[Int] derives CanEqual
        final case class Lead[A](expr: Term[A], offset: Term[Int], default: Maybe[Term[A]]) extends WindowFunction[A] derives CanEqual
        final case class Lag[A](expr: Term[A], offset: Term[Int], default: Maybe[Term[A]])  extends WindowFunction[A] derives CanEqual
        final case class FirstValue[A](expr: Term[A])                                       extends WindowFunction[A] derives CanEqual
        final case class LastValue[A](expr: Term[A])                                        extends WindowFunction[A] derives CanEqual
        final case class NthValue[A](expr: Term[A], n: Term[Int])                           extends WindowFunction[A] derives CanEqual
    end WindowFunction

    // --- OrderSpec / WindowSpec / Frame / FrameBound ---

    final case class OrderSpec(expr: Term[?], direction: OrderSpec.Direction, nulls: OrderSpec.Nulls) derives CanEqual
    object OrderSpec:
        enum Direction derives CanEqual:
            case Asc, Desc
        enum Nulls derives CanEqual:
            case Default, First, Last
    end OrderSpec

    final case class WindowSpec(
        partitionBy: Chunk[Term[?]],
        orderBy: Chunk[OrderSpec],
        frame: Maybe[WindowFrame]
    ) derives CanEqual

    final case class WindowFrame(kind: WindowFrame.Kind, start: FrameBound, end: Maybe[FrameBound]) derives CanEqual
    object WindowFrame:
        enum Kind derives CanEqual:
            case Rows, Range, Groups

    sealed trait FrameBound

    object FrameBound:
        given CanEqual[FrameBound, FrameBound] = CanEqual.derived

        case object UnboundedPreceding           extends FrameBound
        case object UnboundedFollowing           extends FrameBound
        case object CurrentRow                   extends FrameBound
        final case class Preceding(n: Term[Int]) extends FrameBound derives CanEqual
        final case class Following(n: Term[Int]) extends FrameBound derives CanEqual

        inline def unboundedPreceding: FrameBound             = UnboundedPreceding
        inline def unboundedFollowing: FrameBound             = UnboundedFollowing
        inline def currentRow: FrameBound                     = CurrentRow
        inline def preceding(inline n: Int): FrameBound       = Preceding(intLit(n))
        inline def preceding(inline n: Term[Int]): FrameBound = Preceding(n)
        inline def following(inline n: Int): FrameBound       = Following(intLit(n))
        inline def following(inline n: Term[Int]): FrameBound = Following(n)
    end FrameBound

    // --- Projection / OrderingSpecs, eager payloads ---

    /** Payload for SELECT terms, resolved eagerly at AST construction time. */
    sealed trait Projection
    object Projection:
        final case class Resolved(terms: Chunk[Term[?]]) extends Projection derives CanEqual

    /** Payload for ORDER BY, resolved eagerly at AST construction time. */
    sealed trait OrderingSpecs
    object OrderingSpecs:
        final case class Resolved(specs: Chunk[OrderSpec]) extends OrderingSpecs derives CanEqual

    // --- Action / Query / From hierarchy ---

    /** DML super-type (INSERT / UPDATE / DELETE). Parallel sibling to [[Query]], both extend [[Executable]] but are otherwise independent.
      * Replaces the former `Statement` super-type.
      */
    sealed abstract class Action[A] extends Executable[A]

    sealed abstract class Query[A] extends Executable[A] with Term[A]:
        inline def count: Query[Long] = AggregateQuery(this, Aggregate.Count(Maybe.empty, distinct = false))

        inline def exists: Term[Boolean]    = Exists(this)
        inline def notExists: Term[Boolean] = NotExists(this)

        inline def forUpdate: Lock[A]                     = Lock(this, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        inline def forUpdate(inline of: String*): Lock[A] = Lock(this, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.from(of))
        inline def forShare: Lock[A]                      = Lock(this, Lock.Mode.Share, Lock.Behavior.Wait, Chunk.empty)
        inline def forShare(inline of: String*): Lock[A]  = Lock(this, Lock.Mode.Share, Lock.Behavior.Wait, Chunk.from(of))
        inline def forUpdateNoWait: Lock[A]               = Lock(this, Lock.Mode.Update, Lock.Behavior.NoWait, Chunk.empty)
        inline def forUpdateSkipLocked: Lock[A]           = Lock(this, Lock.Mode.Update, Lock.Behavior.SkipLocked, Chunk.empty)
        inline def forShareNoWait: Lock[A]                = Lock(this, Lock.Mode.Share, Lock.Behavior.NoWait, Chunk.empty)
        inline def forShareSkipLocked: Lock[A]            = Lock(this, Lock.Mode.Share, Lock.Behavior.SkipLocked, Chunk.empty)

        inline def union(inline other: Query[A]): Query[A]        = SetOp(SetOp.Kind.Union, this, other)
        inline def unionAll(inline other: Query[A]): Query[A]     = SetOp(SetOp.Kind.UnionAll, this, other)
        inline def intersect(inline other: Query[A]): Query[A]    = SetOp(SetOp.Kind.Intersect, this, other)
        inline def intersectAll(inline other: Query[A]): Query[A] = SetOp(SetOp.Kind.IntersectAll, this, other)
        inline def except(inline other: Query[A]): Query[A]       = SetOp(SetOp.Kind.Except, this, other)
        inline def exceptAll(inline other: Query[A]): Query[A]    = SetOp(SetOp.Kind.ExceptAll, this, other)
    end Query

    /** Companion of [[Query]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions.
      *
      * `.run` / `.runStatic` need a `Query[A]` receiver spliced as its full construction tree so the [[kyo.internal.SqlStaticMacro]] can
      * fold the AST at compile time; that requires an `extension [A](inline q: Query[A])` with an inline parameter (an `inline def` on
      * `Query[A]` sees only `Ident(this)` at the splice site, which cannot be folded). Extensions live in this companion (rather than at
      * the top of the `kyo` package) so they are found by implicit search on any `Query[A]` receiver without polluting the top-level
      * namespace.
      */
    object Query:

        extension [A](inline q: Query[A])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
              *
              * ==Error types==
              * Aborts with [[SqlException]] (any subtype). The two most common decode-time variants:
              *   - [[SqlException.Decode]], a column value could not be converted to the target Scala type. Check the [[SqlSchema]]
              *     derivation for `A`, ensure nullable columns use `Maybe[T]`, and confirm the database schema matches the query result.
              *   - [[SqlException.Unsupported]], the [[SqlSchema]] decoder called a structural read operation (array element, map entry)
              *     that the backend does not yet implement. Re-derive the schema without the unsupported structural type, or supply a
              *     custom decoder via [[SqlSchema.withDecoder]].
              */
            inline def run(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runQueryImpl[A]('q) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runQueryStaticImpl[A]('q) }
        end extension

        extension [A](q: Query[A])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using SqlSchema[A], Frame): Chunk[A] < (Async & Abort[SqlException] & Scope) =
                SqlClient.use { client =>
                    val r = kyo.internal.SqlRender.render(q, client.sqlBackend)
                    client.executeBoundQuery[A](r.sql, r.params)
                }
        end extension
    end Query

    sealed abstract class From[T, F] extends Query[T]:
        def columns: Record[F]

        // -- Joins ----------------------------------------------------------
        inline def innerJoin[T2, F2](inline other: From[T2, F2]): JoinOnBuilder[T, F, T2, F2] = JoinOnBuilder(JoinKind.Inner, this, other)
        inline def leftJoin[T2, F2](inline other: From[T2, F2]): JoinOnBuilder[T, F, T2, F2]  = JoinOnBuilder(JoinKind.Left, this, other)
        inline def rightJoin[T2, F2](inline other: From[T2, F2]): JoinOnBuilder[T, F, T2, F2] = JoinOnBuilder(JoinKind.Right, this, other)
        inline def fullOuterJoin[T2, F2](inline other: From[T2, F2]): JoinOnBuilder[T, F, T2, F2] =
            JoinOnBuilder(JoinKind.FullOuter, this, other)
        inline def crossJoin[T2, F2](inline other: From[T2, F2]): CrossJoin[(T, T2), F & F2] =
            CrossJoin(this, other, columns & other.columns)

        inline def where(inline predicate: Record[F] => Term[Boolean]): Where[T, F] = Where(this, predicate(columns), columns)

        // -- SELECT ---------------------------------------------------------
        inline def select[V](inline projection: Record[F] => Term[V]): Select[T, V] =
            Select(this, Projection.Resolved(Chunk(projection(columns))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline projection: Record[F] => Tup)(using
            ev: IsTupleOfTerms.Aux[Tup, B]
        ): Select[T, B] =
            Select(this, Projection.Resolved(ev.toChunk(projection(columns))), isDistinct = false)

        // -- GROUP BY -------------------------------------------------------
        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        @targetName("groupByLabelled")
        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => LabelledTerm[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, EmptyTuple] & (N ~ GroupedColumn[N, V])] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        @targetName("groupByTuple")
        inline def groupBy[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        // -- GROUP BY ROLLUP ------------------------------------------------
        inline def groupByRollup[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Rollup)
        end groupByRollup

        @targetName("groupByRollupTuple")
        inline def groupByRollup[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Rollup)
        end groupByRollup

        // -- GROUP BY CUBE -------------------------------------------------
        inline def groupByCube[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Cube)
        end groupByCube

        @targetName("groupByCubeTuple")
        inline def groupByCube[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Cube)
        end groupByCube

        // -- GROUP BY GROUPING SETS ----------------------------------------
        // Escape hatch: takes a list of grouping sets explicitly. The post-groupBy view exposes the source view (`columns`);
        // rows where a field is not in the active grouping set carry NULL for that column, so user projections should treat
        // such fields as nullable (e.g. wrap with Maybe).
        inline def groupByGroupingSets(inline sets: Record[F] => Seq[Seq[Term[?]]]): GroupBy[T, F] =
            val nested: Seq[Seq[Term[?]]]     = sets(columns)
            val asSets: Chunk[Chunk[Term[?]]] = Chunk.from(nested.map(s => Chunk.from(s)))
            val flatKeys: Chunk[Term[?]]      = Chunk.from(nested.flatten.distinct)
            new GroupBy(this, flatKeys, columns, Maybe.empty, GroupBy.Kind.GroupingSets(asSets))
        end groupByGroupingSets

        inline def orderBy[A](inline spec: Record[F] => A)(using arg: OrderArg[A]): OrderBy[T] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(columns))))

        inline def count[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = false))
        inline def countDistinct[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = true))
        inline def sum[V: SqlNumeric](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Sum(column(columns), distinct = false))
        inline def avg[V: SqlNumeric](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Avg(column(columns), distinct = false))
        inline def min[V](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Min(column(columns)))
        inline def max[V](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Max(column(columns)))

        inline def limit(inline n: Int): Limit[T]                     = Limit(this, n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[T] = Limit(this, n, offset)
    end From

    /** `columnNames` is `T`'s case-class field names in declaration order (from `SqlMacros.columnNames[T]`). The renderer emits these as
      * the explicit `SELECT` projection for an implicit-projection (`SELECT *`-shaped) query, a literal `Chunk[String]` that lifts via
      * `FromExpr.derived` with zero reflection. It is the reliable Schema-field-order source: the `columns` `Record`'s `Dict` does not
      * preserve declaration order (it reverses for ≤8 fields and is hash-ordered above), so the renderer must not derive order from it.
      */
    final case class Table[T, F](
        columns: Record[F],
        alias: String,
        tableName: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    final case class Nested[T, F](
        columns: Record[F],
        source: Query[?],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    final case class Lateral[T, F](
        columns: Record[F],
        source: Query[?],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    /** `(VALUES (…), (…)) AS alias` query source. `rows` is stored as decomposed pure data, outer `Chunk` = rows, inner `Chunk` = one
      * [[kyo.BoundValue]] per column in declaration order, so the AST lifts via `FromExpr.derived` with zero reflection. The row type `T`
      * is retained for builder-side type-safety only.
      */
    final case class ValuesFrom[T, F](
        columns: Record[F],
        rows: Chunk[Chunk[BoundValue[?]]],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    enum JoinKind derives CanEqual:
        case Inner, Left, Right, FullOuter

    final case class Join[T, F](
        kind: JoinKind,
        left: From[?, ?],
        right: From[?, ?],
        predicate: Term[Boolean],
        columns: Record[F]
    ) extends From[T, F]

    final case class CrossJoin[T, F](
        left: From[?, ?],
        right: From[?, ?],
        columns: Record[F]
    ) extends From[T, F]

    final case class JoinOnBuilder[T1, F1, T2, F2](
        kind: JoinKind,
        left: From[T1, F1],
        right: From[T2, F2]
    ):
        inline def on(inline predicate: Record[F1 & F2] => Term[Boolean]): Join[(T1, T2), F1 & F2] =
            val merged = left.columns & right.columns
            Join(kind, left, right, predicate(merged), merged)
    end JoinOnBuilder

    final case class Where[T, F](
        sql: From[T, F],
        predicate: Term[Boolean],
        columns: Record[F]
    ) extends Query[T]:

        inline def select[V](inline projection: Record[F] => Term[V]): Select[T, V] =
            Select(this, Projection.Resolved(Chunk(projection(columns))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline projection: Record[F] => Tup)(using
            ev: IsTupleOfTerms.Aux[Tup, B]
        ): Select[T, B] =
            Select(this, Projection.Resolved(ev.toChunk(projection(columns))), isDistinct = false)

        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        @targetName("groupByLabelled")
        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => LabelledTerm[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, EmptyTuple] & (N ~ GroupedColumn[N, V])] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        @targetName("groupByTuple")
        inline def groupBy[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Plain)
        end groupBy

        // -- GROUP BY ROLLUP ------------------------------------------------
        inline def groupByRollup[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Rollup)
        end groupByRollup

        @targetName("groupByRollupTuple")
        inline def groupByRollup[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Rollup)
        end groupByRollup

        // -- GROUP BY CUBE -------------------------------------------------
        inline def groupByCube[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, internal.RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Cube)
        end groupByCube

        @targetName("groupByCubeTuple")
        inline def groupByCube[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, internal.RewriteGrouped[FT, internal.ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy(this, ks, kyo.internal.SqlGroupedView.buildGroupedView(this, ks), Maybe.empty, GroupBy.Kind.Cube)
        end groupByCube

        // -- GROUP BY GROUPING SETS ----------------------------------------
        // Escape hatch: takes a list of grouping sets explicitly. The post-groupBy view exposes the source view (`columns`);
        // rows where a field is not in the active grouping set carry NULL for that column, so user projections should treat
        // such fields as nullable (e.g. wrap with Maybe).
        inline def groupByGroupingSets(inline sets: Record[F] => Seq[Seq[Term[?]]]): GroupBy[T, F] =
            val nested: Seq[Seq[Term[?]]]     = sets(columns)
            val asSets: Chunk[Chunk[Term[?]]] = Chunk.from(nested.map(s => Chunk.from(s)))
            val flatKeys: Chunk[Term[?]]      = Chunk.from(nested.flatten.distinct)
            new GroupBy(this, flatKeys, columns, Maybe.empty, GroupBy.Kind.GroupingSets(asSets))
        end groupByGroupingSets

        inline def orderBy[A](inline spec: Record[F] => A)(using arg: OrderArg[A]): OrderBy[T] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(columns))))

        inline def count[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = false))
        inline def countDistinct[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = true))
        inline def sum[V: SqlNumeric](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Sum(column(columns), distinct = false))
        inline def avg[V: SqlNumeric](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Avg(column(columns), distinct = false))
        inline def min[V](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Min(column(columns)))
        inline def max[V](inline column: Record[F] => Term[V]): Query[V] =
            AggregateQuery(this, Aggregate.Max(column(columns)))

        inline def limit(inline n: Int): Limit[T]                     = Limit(this, n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[T] = Limit(this, n, offset)
    end Where

    /** GroupBy[T, F'], F' is the post-groupBy record's F (grouped → `GroupedColumn`, ungrouped → `GroupTerm`).
      *
      * The `view: Record[F]` is materialised eagerly at the `groupBy[…]` construction site (see [[internal.SqlGroupedView]]) so the chain
      * methods (`having` / `select` / `orderBy`) apply the user lambda inline and store the resulting `Term` / `OrderSpec` payloads. The
      * AST is pure data, no lambdas in case-class field positions.
      */
    final case class GroupBy[T, F](
        source: Query[T],
        keys: Chunk[Term[?]],
        view: Record[F],
        havingTerm: Maybe[Term[Boolean]],
        kind: GroupBy.Kind
    ) extends Query[T]:

        inline def having(inline p: Record[F] => Term[Boolean]): GroupBy[T, F] =
            copy(havingTerm = Maybe(p(view)))

        inline def select[V](inline p: Record[F] => Term[V]): Select[T, V] =
            Select(this, Projection.Resolved(Chunk(p(view))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline p: Record[F] => Tup)(using ev: IsTupleOfTerms.Aux[Tup, B]): Select[T, B] =
            Select(this, Projection.Resolved(ev.toChunk(p(view))), isDistinct = false)

        inline def orderBy[A](inline spec: Record[F] => A)(using arg: OrderArg[A]): OrderBy[T] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(view))))

        inline def limit(inline n: Int): Limit[T] = Limit(this, n, 0)
    end GroupBy

    object GroupBy:
        /** Grouping kind, controls how the renderer emits the GROUP BY clause.
          *   - `Plain` → `GROUP BY a, b` (standard, all backends).
          *   - `Rollup` → `GROUP BY ROLLUP (a, b)` on Postgres; `GROUP BY a, b WITH ROLLUP` on MySQL.
          *   - `Cube` → `GROUP BY CUBE (a, b)` on Postgres; `GROUP BY a, b WITH CUBE` on MySQL (MySQL 8.0+).
          *   - `GroupingSets(sets)` → `GROUP BY GROUPING SETS ((a, b), (c), ())` (Postgres; MySQL 8.0+ supports same syntax). Each inner
          *     `Chunk[Term[?]]` is one grouping set; an empty inner chunk renders as `()` (the grand-total grouping).
          */
        sealed trait Kind derives CanEqual
        object Kind:
            case object Plain                                          extends Kind
            case object Rollup                                         extends Kind
            case object Cube                                           extends Kind
            final case class GroupingSets(sets: Chunk[Chunk[Term[?]]]) extends Kind
        end Kind
    end GroupBy

    /** Post-groupBy ungrouped field, only aggregate methods, all returning `Term[V]`. */
    sealed trait GroupTerm[V]:
        private[kyo] def underlying: Column[?, V]
        final inline def sum(using SqlNumeric[V]): Term[V]         = Aggregate.Sum(underlying, distinct = false)
        final inline def sumDistinct(using SqlNumeric[V]): Term[V] = Aggregate.Sum(underlying, distinct = true)
        final inline def avg(using SqlNumeric[V]): Term[V]         = Aggregate.Avg(underlying, distinct = false)
        final inline def avgDistinct(using SqlNumeric[V]): Term[V] = Aggregate.Avg(underlying, distinct = true)
        final inline def min: Term[V]                              = Aggregate.Min(underlying)
        final inline def max: Term[V]                              = Aggregate.Max(underlying)
        final inline def count: Term[Long]                         = Aggregate.Count(Maybe(underlying), distinct = false)
        final inline def countDistinct: Term[Long]                 = Aggregate.Count(Maybe(underlying), distinct = true)
    end GroupTerm

    final case class UngroupedView[V](private[kyo] val underlying: Column[?, V]) extends GroupTerm[V] derives CanEqual

    final case class GroupedColumn[N <: String & Singleton, V](column: Column[N, V])
        extends Term[V] with GroupTerm[V] derives CanEqual:
        private[kyo] def underlying: Column[?, V] = column

    final case class OrderBy[A](sql: Query[A], specs: OrderingSpecs) extends Query[A] derives CanEqual:
        inline def limit(inline n: Int): Limit[A]                     = Limit(this, n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[A] = Limit(this, n, offset)

    final case class Limit[A](sql: Query[A], n: Int, offset: Int) extends Query[A] derives CanEqual

    final case class Lock[A](
        sql: Query[A],
        mode: Lock.Mode,
        behavior: Lock.Behavior,
        ofTables: Chunk[String]
    ) extends Query[A] derives CanEqual
    object Lock:
        enum Mode derives CanEqual:
            case Update, Share
        enum Behavior derives CanEqual:
            case Wait, NoWait, SkipLocked
    end Lock

    final case class SetOp[A](kind: SetOp.Kind, left: Query[A], right: Query[A]) extends Query[A] derives CanEqual
    object SetOp:
        enum Kind derives CanEqual:
            case Union, UnionAll, Intersect, IntersectAll, Except, ExceptAll

    final case class With[A](ctes: Chunk[CommonTable[?]], body: Query[A])          extends Query[A] derives CanEqual
    final case class WithRecursive[A](ctes: Chunk[CommonTable[?]], body: Query[A]) extends Query[A] derives CanEqual

    final case class CommonTable[B](name: String, query: Query[B]) derives CanEqual

    final case class AggregateQuery[A](source: Query[?], agg: Aggregate.Call[A]) extends Query[A] derives CanEqual

    /** A SELECT clause. `terms` is a [[Projection]] (resolved or deferred). */
    final case class Select[A, B](sql: Query[A], terms: Projection, isDistinct: Boolean) extends Query[B] derives CanEqual:
        inline def distinct: Select[A, B] = copy(isDistinct = true)

        /** Coerce the row type to a case class B' when the projected tuple matches B''s field types positionally. (Only meaningful for
          * tuple-formed selects, `B` is the value-type tuple from `IsTupleOfTerms.Out`.)
          */
        inline def to[B2](using m: Mirror.ProductOf[B2] { type MirroredElemTypes = B & Tuple }): Select[A, B2] =
            Select[A, B2](sql, terms, isDistinct)
    end Select

    // --- INSERT / UPDATE / DELETE ---

    /** INSERT statement.
      *
      * @param autoKey
      *   Quoted column name of the auto-incrementing primary key, when one is detected on the target table. `Maybe.Absent` when no auto-key
      *   column is present. The Postgres renderer auto-appends `RETURNING <autoKey>` when this is `Present`. MySQL ignores this field and
      *   reads `last_insert_id` from the OK packet instead.
      *
      * Auto-key detection uses the "first-column-if-Long" fallback (see `Sql.insert`). A follow-up will refine this with proper
      * `@PrimaryKey` / `@AutoIncrement` annotation support.
      */
    final case class Insert[T, F](
        columns: Record[F],
        tableName: String,
        columnNames: Chunk[String],
        source: Insert.Source[T, F],
        overrides: Chunk[SetSpec[?, ?]],
        onConflict: Maybe[Insert.OnConflict[F]],
        autoKey: Maybe[String],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]:
        inline def overriding(inline specs: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
            copy(overrides = overrides ++ Chunk.from(specs.map(_(columns))))
        inline def onConflictDoNothing(inline targets: (Record[F] => Column[? <: String, ?])*): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                source,
                overrides,
                Maybe(Insert.OnConflict.DoNothing(Chunk.from(targets.map(_(columns))))),
                autoKey,
                returning
            )
        inline def onConflictDoUpdate(inline targets: (Record[F] => Column[? <: String, ?])*): OnConflictDoUpdateBuilder[T, F] =
            OnConflictDoUpdateBuilder(this, Chunk.from(targets.map(_(columns))), Maybe.empty)
        inline def returning(inline cols: (Record[F] => Column[? <: String, ?])*): Insert[T, F] =
            copy(returning = Maybe(Chunk.from(cols.map(_(columns)))))
    end Insert

    object Insert:
        sealed abstract class Source[T, F]

        /** `VALUES (…), (…)` rows for an INSERT, stored as decomposed pure data: outer `Chunk` = rows, inner `Chunk` = one
          * [[kyo.BoundValue]] per column in declaration order. The row type `T` is retained for builder-side type-safety only, it is
          * phantom at the node. Decomposing the rows (rather than storing raw `T` instances) keeps the AST pure data so `FromExpr.derived`
          * lifts it with zero reflection.
          */
        final case class Values[T, F](rows: Chunk[Chunk[BoundValue[?]]])                    extends Source[T, F]
        final case class PartialValues[T, F](sets: Chunk[SetSpec[?, ?]])                    extends Source[T, F] derives CanEqual
        final case class FromSelect[T, F, B](columns: Chunk[Column[?, ?]], query: Query[B]) extends Source[T, F] derives CanEqual

        sealed trait OnConflict[F]
        object OnConflict:
            final case class DoNothing[F](targets: Chunk[Column[?, ?]]) extends OnConflict[F] derives CanEqual
            final case class DoUpdate[F](
                targets: Chunk[Column[?, ?]],
                sets: Chunk[SetSpec[?, ?]],
                where: Maybe[Term[Boolean]]
            ) extends OnConflict[F] derives CanEqual
        end OnConflict

        extension [T, F](inline ins: Insert[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runInsertImpl[T, F]('ins) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runInsertStaticImpl[T, F]('ins) }
        end extension

        extension [T, F](ins: Insert[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using frame: Frame): InsertResult < (Async & Abort[SqlException] & Scope) =
                SqlClient.use { client =>
                    val r = kyo.internal.SqlRender.render(ins, client.sqlBackend, frame)
                    client.executeBoundInsert(r.sql, r.params)
                }
        end extension
    end Insert

    final case class InsertBuilder[T, F](
        columns: Record[F],
        tableName: String,
        columnNames: Chunk[String],
        autoKey: Maybe[String] = Maybe.empty
    ):
        inline def values(inline rows: T*): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                Insert.Values(kyo.internal.SqlMacros.rowValues[T](rows)),
                Chunk.empty,
                Maybe.empty,
                autoKey,
                Maybe.empty
            )
        @targetName("valuesSeq")
        inline def values(inline rows: Seq[T]): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                Insert.Values(kyo.internal.SqlMacros.rowValues[T](rows)),
                Chunk.empty,
                Maybe.empty,
                autoKey,
                Maybe.empty
            )
        inline def partialValues(inline specs: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                Insert.PartialValues(Chunk.from(specs.map(_(columns)))),
                Chunk.empty,
                Maybe.empty,
                autoKey,
                Maybe.empty
            )
        inline def fromSelect[B](inline cols: (Record[F] => Column[? <: String, ?])*)(inline query: Query[B]): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                Insert.FromSelect(Chunk.from(cols.map(_(columns))), query),
                Chunk.empty,
                Maybe.empty,
                autoKey,
                Maybe.empty
            )
    end InsertBuilder

    final case class OnConflictDoUpdateBuilder[T, F](
        insert: Insert[T, F],
        targets: Chunk[Column[?, ?]],
        whereClause: Maybe[Term[Boolean]]
    ):
        inline def where(inline predicate: Record[F] => Term[Boolean]): OnConflictDoUpdateBuilder[T, F] =
            copy(whereClause = Maybe(predicate(insert.columns)))
        inline def apply(inline sets: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
            Insert[T, F](
                insert.columns,
                insert.tableName,
                insert.columnNames,
                insert.source,
                insert.overrides,
                Maybe(Insert.OnConflict.DoUpdate(targets, Chunk.from(sets.map(_(insert.columns))), whereClause)),
                insert.autoKey,
                insert.returning
            )
    end OnConflictDoUpdateBuilder

    final case class Update[T, F](
        columns: Record[F],
        tableName: String,
        sets: Chunk[SetSpec[?, ?]],
        whereClause: Maybe[Term[Boolean]],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]

    /** Companion of [[Update]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions (see [[Query$]] for the rationale). */
    object Update:

        extension [T, F](inline upd: Update[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runUpdateImpl[T, F]('upd) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runUpdateStaticImpl[T, F]('upd) }
        end extension

        extension [T, F](upd: Update[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                SqlClient.use { client =>
                    val r = kyo.internal.SqlRender.render(upd, client.sqlBackend)
                    client.executeBoundUpdate(r.sql, r.params)
                }
        end extension
    end Update

    final case class UpdateBuilder[T, F](
        columns: Record[F],
        tableName: String,
        sets: Chunk[SetSpec[?, ?]] = Chunk.empty
    ):
        inline def set(inline specs: (Record[F] => SetSpec[? <: String, ?])*): UpdateBuilder[T, F] =
            copy(sets = sets ++ Chunk.from(specs.map(_(columns))))
        inline def where(inline predicate: Record[F] => Term[Boolean]): Update[T, F] =
            Update[T, F](columns, tableName, sets, Maybe(predicate(columns)), Maybe.empty)
        inline def build: Update[T, F] = Update[T, F](columns, tableName, sets, Maybe.empty, Maybe.empty)
        inline def returning(inline cols: (Record[F] => Column[? <: String, ?])*): UpdateReturningBuilder[T, F] =
            UpdateReturningBuilder(columns, tableName, sets, Chunk.from(cols.map(_(columns))))
    end UpdateBuilder

    final case class UpdateReturningBuilder[T, F](
        columns: Record[F],
        tableName: String,
        sets: Chunk[SetSpec[?, ?]],
        returningCols: Chunk[Column[?, ?]]
    ):
        inline def where(inline predicate: Record[F] => Term[Boolean]): Update[T, F] =
            Update[T, F](columns, tableName, sets, Maybe(predicate(columns)), Maybe(returningCols))
        inline def build: Update[T, F] =
            Update[T, F](columns, tableName, sets, Maybe.empty, Maybe(returningCols))
    end UpdateReturningBuilder

    final case class Delete[T, F](
        columns: Record[F],
        tableName: String,
        whereClause: Maybe[Term[Boolean]],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]

    /** Companion of [[Delete]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions (see [[Query$]] for the rationale). */
    object Delete:

        extension [T, F](inline del: Delete[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runDeleteImpl[T, F]('del) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                ${ kyo.internal.SqlRunMacro.runDeleteStaticImpl[T, F]('del) }
        end extension

        extension [T, F](del: Delete[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using Frame): Long < (Async & Abort[SqlException] & Scope) =
                SqlClient.use { client =>
                    val r = kyo.internal.SqlRender.render(del, client.sqlBackend)
                    client.executeBoundUpdate(r.sql, r.params)
                }
        end extension
    end Delete

    final case class DeleteBuilder[T, F](
        columns: Record[F],
        tableName: String
    ):
        inline def where(inline predicate: Record[F] => Term[Boolean]): Delete[T, F] =
            Delete[T, F](columns, tableName, Maybe(predicate(columns)), Maybe.empty)
        inline def build: Delete[T, F] = Delete[T, F](columns, tableName, Maybe.empty, Maybe.empty)
        inline def returning(inline cols: (Record[F] => Column[? <: String, ?])*): DeleteReturningBuilder[T, F] =
            DeleteReturningBuilder(columns, tableName, Chunk.from(cols.map(_(columns))))
    end DeleteBuilder

    final case class DeleteReturningBuilder[T, F](
        columns: Record[F],
        tableName: String,
        returningCols: Chunk[Column[?, ?]]
    ):
        inline def where(inline predicate: Record[F] => Term[Boolean]): Delete[T, F] =
            Delete[T, F](columns, tableName, Maybe(predicate(columns)), Maybe(returningCols))
        inline def build: Delete[T, F] =
            Delete[T, F](columns, tableName, Maybe.empty, Maybe(returningCols))
    end DeleteReturningBuilder

    final case class SetSpec[N <: String & Singleton, V](column: Column[N, V], value: Term[V]) derives CanEqual

    // --- CASE WHEN builder ---

    final case class WhenBuilder(predicate: Term[Boolean]) derives CanEqual:

        inline def to[A](inline value: Term[A]): CaseBuilder[A] = CaseBuilder(Chunk((predicate, value)))

        @targetName("toRaw")
        inline def to[A](inline value: A)(using s: SqlSchema[A]): CaseBuilder[A] = CaseBuilder(Chunk((predicate, Literal(value, s))))
    end WhenBuilder

    final case class CaseBuilder[A](whens: Chunk[(Term[Boolean], Term[A])]) derives CanEqual:

        inline def when(inline predicate: Term[Boolean]): WhenContinuation[A] = WhenContinuation(whens, predicate)

        inline def otherwise(inline value: Term[A]): Term[A] = CaseExpr(whens, value)

        @targetName("otherwiseRaw")
        inline def otherwise(inline value: A)(using s: SqlSchema[A]): Term[A] = CaseExpr(whens, Literal(value, s))

        inline def otherwiseNull: Term[Maybe[A]] = CaseExprNullable(whens)
    end CaseBuilder

    final case class WhenContinuation[A](whens: Chunk[(Term[Boolean], Term[A])], predicate: Term[Boolean]) derives CanEqual:

        inline def to(inline value: Term[A]): CaseBuilder[A] = CaseBuilder(whens :+ ((predicate, value)))

        @targetName("toRaw")
        inline def to(inline value: A)(using s: SqlSchema[A]): CaseBuilder[A] = CaseBuilder(whens :+ ((predicate, Literal(value, s))))
    end WhenContinuation

    // --- Source-construction builders ---

    final class FromBuilder[T]:
        transparent inline def apply[N <: String & Singleton](alias: N)(using f: Fields[T]) =
            val cols = internal.buildColumns[T, N](alias)
            Table[T, internal.RecordF[cols.type]](cols, alias, kyo.internal.SqlMacros.tableName[T], kyo.internal.SqlMacros.columnNames[T])
        end apply
    end FromBuilder

    final class NestedBuilder[T]:
        transparent inline def apply[N <: String & Singleton](alias: N, query: Query[?])(using f: Fields[T]) =
            val cols = internal.buildColumns[T, N](alias)
            Nested[T, internal.RecordF[cols.type]](cols, query, alias, kyo.internal.SqlMacros.columnNames[T])
        end apply
    end NestedBuilder

    final class LateralBuilder[T]:
        transparent inline def apply[N <: String & Singleton](alias: N, query: Query[?])(using f: Fields[T]) =
            val cols = internal.buildColumns[T, N](alias)
            Lateral[T, internal.RecordF[cols.type]](cols, query, alias, kyo.internal.SqlMacros.columnNames[T])
        end apply
    end LateralBuilder

    final class ValuesBuilder[T]:
        transparent inline def apply[N <: String & Singleton](alias: N, inline rows: T*)(using
            m: Mirror.ProductOf[T],
            f: Fields[T]
        ) =
            val cols = internal.buildColumns[T, N](alias)
            ValuesFrom[T, internal.RecordF[cols.type]](
                cols,
                kyo.internal.SqlMacros.rowValues[T](rows),
                alias,
                kyo.internal.SqlMacros.columnNames[T]
            )
        end apply
    end ValuesBuilder

    // --- Typeclasses ---

    sealed trait SqlNumeric[A]
    object SqlNumeric:
        given int: SqlNumeric[Int]                                   = new SqlNumeric[Int] {}
        given long: SqlNumeric[Long]                                 = new SqlNumeric[Long] {}
        given float: SqlNumeric[Float]                               = new SqlNumeric[Float] {}
        given double: SqlNumeric[Double]                             = new SqlNumeric[Double] {}
        given bigDecimal: SqlNumeric[BigDecimal]                     = new SqlNumeric[BigDecimal] {}
        given nullable[A](using SqlNumeric[A]): SqlNumeric[Maybe[A]] = new SqlNumeric[Maybe[A]] {}
    end SqlNumeric

    sealed trait IsNullable[A]
    object IsNullable:
        given maybe[A]: IsNullable[Maybe[A]] = new IsNullable[Maybe[A]] {}

    /** Inductive proof that `Tup` is a tuple whose every element is some subtype of `Term[?]` (e.g. `Column[N, V]` extends `Term[V]`).
      * Provides safe element-by-element extraction into a `Chunk[Term[?]]` and exposes the value-type tuple via `Out` (used by
      * `Select.to[B2]`).
      */
    sealed trait IsTupleOfTerms[Tup <: Tuple]:
        type Out <: Tuple
        def toChunk(t: Tup): Chunk[Term[?]]
    object IsTupleOfTerms:
        type Aux[Tup <: Tuple, O <: Tuple] = IsTupleOfTerms[Tup] { type Out = O }

        given empty: Aux[EmptyTuple, EmptyTuple] = new IsTupleOfTerms[EmptyTuple]:
            type Out = EmptyTuple
            def toChunk(t: EmptyTuple): Chunk[Term[?]] = Chunk.empty

        given cons[H, Tail <: Tuple, OTail <: Tuple, TermH <: Term[H]](
            using rest: Aux[Tail, OTail]
        ): Aux[TermH *: Tail, H *: OTail] = new IsTupleOfTerms[TermH *: Tail]:
            type Out = H *: OTail
            def toChunk(t: TermH *: Tail): Chunk[Term[?]] = t.head +: rest.toChunk(t.tail)
    end IsTupleOfTerms

    /** Disambiguator for `orderBy`: lets one overload accept either a single `OrderSpec` or a tuple of `OrderSpec`s without overload
      * ambiguity. Inductive given derivation prevents silent drops.
      */
    sealed trait OrderArg[A]:
        def toChunk(a: A): Chunk[OrderSpec]

    object OrderArg:
        given single: OrderArg[OrderSpec] with
            def toChunk(a: OrderSpec): Chunk[OrderSpec] = Chunk(a)
        given empty: OrderArg[EmptyTuple] with
            def toChunk(a: EmptyTuple): Chunk[OrderSpec] = Chunk.empty
        given cons[T <: Tuple](using rest: OrderArg[T]): OrderArg[OrderSpec *: T] with
            def toChunk(a: OrderSpec *: T): Chunk[OrderSpec] = a.head +: rest.toChunk(a.tail)
    end OrderArg

    // --- Internal type machinery and helpers ---

    object internal:
        type ColumnFields[T <: Tuple] = T match
            case EmptyTuple      => Any
            case (n ~ v) *: rest => (n ~ Column[n & String, v]) & ColumnFields[rest]

        type RecordF[R] = R match
            case Record[f] => f

        type ExtractTermValueTypes[T <: Tuple] <: Tuple = T match
            case EmptyTuple      => EmptyTuple
            case Term[v] *: rest => v *: ExtractTermValueTypes[rest]

        type ColumnsToFields[T <: Tuple] <: Tuple = T match
            case EmptyTuple           => EmptyTuple
            case Column[n, ?] *: rest => (n ~ Unit) *: ColumnsToFields[rest]

        type GroupedG[GroupedFs <: Tuple] =
            [n <: String & Singleton, v] =>> Fields.IfHasName[GroupedFs, n, GroupedColumn[n, v], GroupTerm[v]]

        type RewriteGrouped[FT <: Tuple, GroupedFs <: Tuple] =
            Fields.Join[Tuple.Map[FT, Record.~.MapNamedValue[GroupedG[GroupedFs]]]]

        /** Per-field SQL-name resolver used by `buildColumns` / `buildRowColumns` to populate `Column.sqlName`.
          *
          * Uses `summonFrom` to find the `SqlSchema[T]` in scope and delegates to runtime `SqlNameResolver.columnName`. The result is a
          * runtime call (not a literal), so `Column.sqlName` is opaque to the static-SQL macro's lift step.
          * `SqlStaticMacro.emitOpaqueCauses` detects this pattern and produces a positioned error directing the user to `.run` for
          * queries whose columns participate in `.runStatic` and have schema-driven name overrides.
          *
          * Why this isn't a macro that constant-folds: the per-field name singleton lives behind a polyfunction parameter
          * `[n <: String & Singleton, v]` passed to `Record.stageNamed`. Macro expansion fires at polyfunction-body type-check time
          * (with `n` still abstract), not at per-field substitution time inside `stageNamedLoop`. Without a Scala 3 mechanism to defer
          * macro expansion past polyfunction substitution, we can't constant-fold the resolved name from this site.
          */
        private[kyo] inline def resolveSqlName[T](scalaName: String): String = summonFrom {
            case s: SqlSchema[T] => kyo.internal.SqlNameResolver.columnName(scalaName, s)
            case _               => scalaName
        }

        transparent inline def buildColumns[T, N <: String & Singleton](alias: N)(using Fields[T]) =
            alias ~ Record.stageNamed[T] {
                [n <: String & Singleton, v] =>
                    (g: Field[n, v]) =>
                        Column[n & String, v](alias, g.name, resolveSqlName[T](g.name))
            }

        transparent inline def buildRowColumns[T](using Fields[T]) =
            Record.stageNamed[T] {
                [n <: String & Singleton, v] =>
                    (g: Field[n, v]) =>
                        Column[n & String, v]("", g.name, resolveSqlName[T](g.name))
            }
    end internal

    private[kyo] inline def intLit(inline n: Int): Term[Int] = Literal(n, summon[SqlSchema[Int]])
    private[kyo] inline def oneInt: Term[Int]                = intLit(1)

end SqlAst
