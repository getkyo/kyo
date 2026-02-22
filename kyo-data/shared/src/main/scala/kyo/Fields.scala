package kyo

import kyo.Record2.*
import scala.compiletime.*

/** Reifies the metadata of an intersection of `~[Name, Value]` field types.
  *
  * Decomposes intersection types (e.g., `"name" ~ String & "age" ~ Int`) into a tuple of components, then provides operations over the
  * field structure: field names, Field instances, type-level mapping, and inline iteration.
  *
  * This is the single macro-powered abstraction — all other operations (Record2 field access, update, etc.) are built on pure Scala.
  */
sealed abstract class Fields[A] extends Serializable:

    /** Tuple representation: `"a" ~ Int & "b" ~ String` → `("a" ~ Int) *: ("b" ~ String) *: EmptyTuple` */
    type AsTuple <: Tuple

    /** Applies a type constructor to each component and re-intersects: `Fields["a" ~ Int & "b" ~ String].Map[F]` =
      * `F["a" ~ Int] & F["b" ~ String]`
      */
    type Map[F[_]] = Fields.Join[Tuple.Map[AsTuple, F]]

    /** Runtime field names, materialized by the macro. */
    val names: Set[String]

    /** Runtime Field descriptors, lazily materialized. */
    lazy val fields: List[Field[?, ?]]

end Fields

object Fields:

    private[kyo] def createAux[A, T <: Tuple](_names: Set[String], _fields: => List[Field[?, ?]]): Fields.Aux[A, T] =
        new Fields[A]:
            type AsTuple = T
            val names       = _names
            lazy val fields = _fields

    private type Join[A <: Tuple] = Tuple.Fold[A, Any, [B, C] =>> B & C]

    type Aux[A, T] =
        Fields[A]:
            type AsTuple = T

    transparent inline given derive[A]: Fields[A] =
        ${ internal.FieldsMacros.deriveImpl[A] }

    /** Summon Field instances for each component in A. */
    def fields[A](using f: Fields[A]): List[Field[?, ?]] = f.fields

    /** Collect field names from A. */
    def names[A](using f: Fields[A]): Set[String] = f.names

    opaque type SummonAll[A, F[_]] = Map[String, F[Any]]

    extension [A, F[_]](sa: SummonAll[A, F])
        def get(name: String): F[Any]            = sa(name)
        def contains(name: String): Boolean      = sa.contains(name)
        def iterator: Iterator[(String, F[Any])] = sa.iterator
    end extension

    object SummonAll:
        inline given [A, F[_]](using f: Fields[A]): SummonAll[A, F] =
            summonLoop[f.AsTuple, F]

        private inline def summonLoop[T <: Tuple, F[_]]: Map[String, F[Any]] =
            inline erasedValue[T] match
                case _: EmptyTuple => Map.empty
                case _: ((n ~ v) *: rest) =>
                    summonLoop[rest, F].updated(constValue[n & String], summonInline[F[v]].asInstanceOf[F[Any]])
    end SummonAll

    sealed abstract class Have[F, Name <: String]:
        type Value

    object Have:
        private[kyo] def unsafe[F, Name <: String, V]: Have[F, Name] { type Value = V } =
            new Have[F, Name]:
                type Value = V

        transparent inline given [F, Name <: String]: Have[F, Name] =
            ${ internal.FieldsMacros.haveImpl[F, Name] }
    end Have

    // --- Comparable ---

    opaque type Comparable[A] = Unit

    object Comparable:
        private[kyo] def unsafe[A]: Comparable[A] = ()

        transparent inline given derive[A]: Comparable[A] =
            ${ internal.FieldsMacros.comparableImpl[A] }
    end Comparable

end Fields
