package kyo

import kyo.Record.*
import scala.compiletime.*

/** Reifies the structure of an intersection of `Name ~ Value` field types into runtime metadata and type-level operations.
  *
  * Given a field type like `"name" ~ String & "age" ~ Int`, a `Fields` instance decomposes it into a tuple of individual field components
  * (`("name" ~ String) *: ("age" ~ Int) *: EmptyTuple`) and provides both runtime access (field names, `Field` descriptors) and type-level
  * transformations (mapping, value extraction, zipping). Also supports case class types by deriving the equivalent field intersection from
  * the product's element labels and types.
  *
  * Instances are derived transparently via a macro (`Fields.derive`), and are summoned implicitly by `Record` operations like `map`,
  * `compact`, `values`, and `zip`.
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

    /** Runtime `Field` descriptors (name, tag, nested), lazily materialized. */
    lazy val fields: List[Field[?, ?]]

    /** The set of field names, derived from `fields`. */
    def names: Set[String] = fields.iterator.map(_.name).toSet

end Fields

/** Companion providing derivation, type-level utilities, and evidence types for field operations. */
object Fields:

    private[kyo] def createAux[A, T <: Tuple](_fields: => List[Field[?, ?]]): Fields.Aux[A, T] =
        new Fields[A]:
            type AsTuple = T
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
        def foreach(fn: (String, F[Any]) => Unit): Unit = sa.foreach((k, v) => fn(k, v))
    end extension

    object SummonAll:
        /** Inline given that summons `F[V]` for each field `Name ~ V` in `A` and collects them into a map keyed by field name. Fails at
          * compile time if any field's value type lacks an `F` instance.
          */
        inline given [A, F[_]](using f: Fields[A]): SummonAll[A, F] =
            summonLoop[f.AsTuple, F]

        // Note: uses Map instead of Dict because Dict (an opaque type) causes the compiler
        // to hang when used inside inline recursive methods, likely due to cascading inline expansion.
        private inline def summonLoop[T <: Tuple, F[_]]: Map[String, F[Any]] =
            inline erasedValue[T] match
                case _: EmptyTuple => Map.empty
                case _: ((n1 ~ v1) *: (n2 ~ v2) *: (n3 ~ v3) *: (n4 ~ v4) *: rest) =>
                    summonLoop[rest, F]
                        .updated(constValue[n1 & String], summonInline[F[v1]].asInstanceOf[F[Any]])
                        .updated(constValue[n2 & String], summonInline[F[v2]].asInstanceOf[F[Any]])
                        .updated(constValue[n3 & String], summonInline[F[v3]].asInstanceOf[F[Any]])
                        .updated(constValue[n4 & String], summonInline[F[v4]].asInstanceOf[F[Any]])
                case _: ((n ~ v) *: rest) =>
                    summonLoop[rest, F].updated(constValue[n & String], summonInline[F[v]].asInstanceOf[F[Any]])
    end SummonAll

    /** Evidence that field type `F` contains a field named `Name`. The dependent `Value` member resolves to the field's value type. Used by
      * `Record.selectDynamic` and `Record.getField` to verify field access at compile time and infer the return type.
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

    /** Opaque evidence that all value types in field type `A` have `CanEqual` instances, enabling `==` comparisons on `Record[A]`. */
    opaque type Comparable[A] = Unit

    object Comparable:
        private[kyo] def unsafe[A]: Comparable[A] = ()

        /** Macro-derived given that verifies all field value types in `A` have `CanEqual`. Fails at compile time if any field type lacks
          * `CanEqual`.
          */
        transparent inline given derive[A]: Comparable[A] =
            ${ internal.FieldsMacros.comparableImpl[A] }
    end Comparable

    /** Opaque evidence that field types `A` and `B` have the same set of field names, enabling type-safe `zip`. */
    opaque type SameNames[A, B] = Unit

    object SameNames:
        private[kyo] def unsafe[A, B]: SameNames[A, B] = ()

        /** Macro-derived given that verifies `A` and `B` have identical field names. Fails at compile time if they differ. */
        transparent inline given derive[A, B]: SameNames[A, B] =
            ${ internal.FieldsMacros.sameNamesImpl[A, B] }
    end SameNames

    /** Prevents Scala from merging field names when chaining field definitions.
      *
      * Because `~` is contravariant in its name parameter, Scala normalizes intersections like `"name" ~ Int & "age" ~ Int` into
      * `("name" | "age") ~ Int`, collapsing distinct fields into a single union. Requiring `using Pin[N]` pins each field name during
      * inference, keeping them as separate intersection members. The runtime value is just `()` — this is purely a compile-time mechanism.
      *
      * {{{
      * // Without Pin — chaining merges names: "name" ~ A & "age" ~ B becomes ("name" | "age") ~ (A & B)
      * def field[N <: String & Singleton](name: N): Def[In & N ~ Int]
      *
      * // With Pin — each field name is preserved: "name" ~ A & "age" ~ B stays separate
      * def field[N <: String & Singleton](name: N)(using Pin[N]): Def[In & N ~ Int]
      * }}}
      *
      * Note: both `Pin` and `Exact` can be replaced by `Precise` (from `scala.language.experimental.modularity`) once it becomes stable.
      * `Precise` prevents type widening directly on the type parameter:
      * {{{
      * def field[N <: String & Singleton : Precise](name: N): Def[In & N ~ Int]
      * }}}
      */
    private object Pin:
        opaque type Pin[+N <: String] = Unit
        given [N <: String]: Pin[N] = ()
    end Pin
    export Pin.*

    /** Decomposes a function's return type into a type constructor and its field types, preserving field information that Scala would
      * otherwise widen.
      *
      * With a direct approach like `def modify[A >: Fields](f: Def[Fields] => Def[A])`, Scala widens `A` to the lower bound through the
      * lambda, losing field types. `Exact` avoids this by first inferring `R` as the full return type (with no bound forcing widening),
      * then extracting the field types.
      *
      * {{{
      * // Without Exact — A is widened to Any, field types are lost
      * def modify[A >: Fields](f: Def[Fields] => Def[A]): Record[A]
      *
      * // With Exact — R preserves the full type, field types are extracted as s.Out
      * def modify[R](f: Def[Fields] => R)(using s: Exact[Def, R]): Record[s.Out]
      * }}}
      *
      * Note: both `Pin` and `Exact` can be replaced by `Precise` (from `scala.language.experimental.modularity`) once it becomes stable.
      * `Precise` prevents the widening directly, removing the need to decompose `R`:
      * {{{
      * def modify[A : Precise](f: Def[Fields] => Def[A]): Record[A]
      * }}}
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
