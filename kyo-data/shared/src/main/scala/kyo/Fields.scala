package kyo

import kyo.Record2.*
import scala.compiletime.*

/** Reifies the structure of an intersection of `Name ~ Value` field types into runtime metadata and type-level operations.
  *
  * Given a field type like `"name" ~ String & "age" ~ Int`, a `Fields` instance decomposes it into a tuple of individual field components
  * (`("name" ~ String) *: ("age" ~ Int) *: EmptyTuple`) and provides both runtime access (field names, `Field` descriptors) and type-level
  * transformations (mapping, value extraction, zipping). Also supports case class types by deriving the equivalent field intersection from
  * the product's element labels and types.
  *
  * Instances are derived transparently via a macro (`Fields.derive`), and are summoned implicitly by `Record2` operations like `map`,
  * `compact`, `values`, and `zip`. This is the single macro-powered abstraction in the Record2 system — all other operations are built on
  * pure Scala using the metadata that `Fields` provides.
  *
  * @tparam A
  *   The field intersection type (e.g., `"name" ~ String & "age" ~ Int`) or a case class type
  */
sealed abstract class Fields[A] extends Serializable:

    /** Tuple representation of the fields: `"a" ~ Int & "b" ~ String` becomes `("a" ~ Int) *: ("b" ~ String) *: EmptyTuple`. */
    type AsTuple <: Tuple

    /** Applies a type constructor `F` to each field component and re-intersects the results. For example,
      * `Fields["a" ~ Int & "b" ~ String].Map[Option]` yields `Option["a" ~ Int] & Option["b" ~ String]`.
      */
    type Map[F[_]] = Fields.Join[Tuple.Map[AsTuple, F]]

    /** Extracts the value types from each field component into a plain tuple: `Fields["a" ~ Int & "b" ~ String].Values` = `(Int, String)`.
      */
    type Values = Fields.ExtractValues[AsTuple]

    /** Zips this field tuple with another by name, pairing their value types into tuples. */
    type Zipped[T2 <: Tuple] = Fields.ZipValues[AsTuple, T2]

    /** The set of field names, materialized at runtime by the macro. */
    val names: Set[String]

    /** Runtime `Field` descriptors (name, tag, nested), lazily materialized. */
    lazy val fields: List[Field[?, ?]]

end Fields

/** Companion providing derivation, type-level utilities, and evidence types for field operations. */
object Fields:

    private[kyo] def createAux[A, T <: Tuple](_names: Set[String], _fields: => List[Field[?, ?]]): Fields.Aux[A, T] =
        new Fields[A]:
            type AsTuple = T
            val names       = _names
            lazy val fields = _fields

    private[kyo] type Join[A <: Tuple] = Tuple.Fold[A, Any, [B, C] =>> B & C]

    /** Match type that extracts value types from a tuple of field components into a plain tuple. */
    type ExtractValues[T <: Tuple] <: Tuple = T match
        case EmptyTuple      => EmptyTuple
        case (n ~ v) *: rest => v *: ExtractValues[rest]

    /** Match type that looks up the value type for field name `N` in a tuple of field components. */
    type LookupValue[N <: String, T <: Tuple] = T match
        case (N ~ v) *: _ => v
        case _ *: rest    => LookupValue[N, rest]

    /** Match type that zips two field tuples by name, pairing their value types into `(V1, V2)` tuples. */
    type ZipValues[T1 <: Tuple, T2 <: Tuple] = T1 match
        case EmptyTuple             => Any
        case (n ~ v1) *: EmptyTuple => n ~ (v1, LookupValue[n, T2])
        case (n ~ v1) *: rest       => (n ~ (v1, LookupValue[n, T2])) & ZipValues[rest, T2]

    /** Refinement type alias that exposes the `AsTuple` member. */
    type Aux[A, T] =
        Fields[A]:
            type AsTuple = T

    /** Macro-derived given that produces a `Fields` instance for any field intersection type or case class. */
    transparent inline given derive[A]: Fields[A] =
        ${ internal.FieldsMacros.deriveImpl[A] }

    /** Returns the `Field` descriptors for type `A`. Convenience accessor for `summon[Fields[A]].fields`. */
    def fields[A](using f: Fields[A]): List[Field[?, ?]] = f.fields

    /** Returns the field names for type `A`. Convenience accessor for `summon[Fields[A]].names`. */
    def names[A](using f: Fields[A]): Set[String] = f.names

    /** An opaque map from field name to a type class instance `F[Any]`, summoned inline for each field's value type. Used by operations
      * like `Render` that need a type class instance per field.
      */
    opaque type SummonAll[A, F[_]] = Map[String, F[Any]]

    extension [A, F[_]](sa: SummonAll[A, F])
        /** Retrieves the type class instance for the given field name. */
        def get(name: String): F[Any] = sa(name)

        /** Returns true if an instance exists for the given field name. */
        def contains(name: String): Boolean = sa.contains(name)

        /** Iterates over all (name, instance) pairs. */
        def iterator: Iterator[(String, F[Any])] = sa.iterator
    end extension

    object SummonAll:
        /** Inline given that summons `F[V]` for each field `Name ~ V` in `A` and collects them into a map keyed by field name. Fails at
          * compile time if any field's value type lacks an `F` instance.
          */
        inline given [A, F[_]](using f: Fields[A]): SummonAll[A, F] =
            summonLoop[f.AsTuple, F]

        private inline def summonLoop[T <: Tuple, F[_]]: Map[String, F[Any]] =
            inline erasedValue[T] match
                case _: EmptyTuple => Map.empty
                case _: ((n ~ v) *: rest) =>
                    summonLoop[rest, F].updated(constValue[n & String], summonInline[F[v]].asInstanceOf[F[Any]])
    end SummonAll

    /** Evidence that field type `F` contains a field named `Name`. The dependent `Value` member resolves to the field's value type. Used by
      * `Record2.selectDynamic` and `Record2.getField` to verify field access at compile time and infer the return type.
      */
    sealed abstract class Have[F, Name <: String]:
        type Value

    object Have:
        private[kyo] def unsafe[F, Name <: String, V]: Have[F, Name] { type Value = V } =
            new Have[F, Name]:
                type Value = V

        /** Macro-derived given that resolves the value type for `Name` in `F`. Fails at compile time if the field does not exist. */
        transparent inline given [F, Name <: String]: Have[F, Name] =
            ${ internal.FieldsMacros.haveImpl[F, Name] }
    end Have

    /** Opaque evidence that all value types in field type `A` have `CanEqual` instances, enabling `==` comparisons on `Record2[A]`. */
    opaque type Comparable[A] = Unit

    object Comparable:
        private[kyo] def unsafe[A]: Comparable[A] = ()

        /** Macro-derived given that verifies all field value types in `A` have `CanEqual`. Fails at compile time if any field type lacks
          * `CanEqual`.
          */
        transparent inline given derive[A]: Comparable[A] =
            ${ internal.FieldsMacros.comparableImpl[A] }
    end Comparable

    /** Anchors a field name type variable so the compiler commits to its precise singleton type immediately.
      *
      * **Problem:** When a builder method introduces a type variable `N <: String & Singleton` into an intersection (e.g.,
      * `def query[N <: ...](name: N): Def[In & N ~ A]`), Scala 3 may defer resolving `N` and later normalize the intersection, collapsing
      * `"page" ~ Int & "sort" ~ String` into `("page" | "sort") ~ (Int | String)`.
      *
      * **Solution:** Adding `(using Pin[N])` forces the compiler to solve `N` eagerly at each call site, preserving each field as a
      * distinct intersection component. Only needed on methods with a type variable `N` — methods with a concrete literal like `"body"`
      * don't need it.
      *
      * This is a workaround for the absence of `Precise` (SIP-64). `Pin` and `Exact` work together: `Pin` anchors names at each builder
      * step, while `Exact` preserves the full intersection at lambda boundaries.
      *
      * @tparam N
      *   the singleton string field name to anchor
      */
    private object Pin:
        opaque type Pin[+N <: String] = Unit
        given [N <: String]: Pin[N] = ()
    end Pin
    export Pin.*

    /** Preserves the precise intersection type inferred inside a builder lambda at the point where it escapes.
      *
      * **Problem:** When a lambda like `_.query[Int]("page").header[String]("sort")` returns `Def[A]`, the compiler infers `A` as an
      * intersection type internally, but may normalize/widen it when assigning the result to the outer scope, losing the distinct field
      * structure.
      *
      * **Solution:** `Exact[F, R]` pattern-matches the lambda's return type `R` against `F[A]`, extracting `A` as a dependent type (`Out`).
      * Because `A` is captured via unification rather than inference, the compiler preserves the exact intersection. The `apply` method
      * provides an identity conversion back to `F[Out]`.
      *
      * This is a workaround for the absence of `Precise` (SIP-64). `Pin` and `Exact` work together: `Pin` anchors names at each builder
      * step, while `Exact` preserves the full intersection at lambda boundaries.
      */
    sealed trait Exact[F[_], R]:
        type Out
        def apply(r: R): F[Out]

    object Exact:
        given [F[_], A]: Exact[F, F[A]] with
            type Out = A
            def apply(r: F[A]): F[A] = r
    end Exact

end Fields
