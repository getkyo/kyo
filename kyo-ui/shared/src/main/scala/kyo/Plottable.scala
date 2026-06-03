package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.internal.Domain
import kyo.internal.NumberFormat
import kyo.internal.Scale
import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** Maps a static value type to the scale that plots it.
  *
  * Open: the library ships built-in instances for `Int`, `Long`, `Double`, `String`, and `Instant`; enum types
  * derive instances automatically; opaque numeric quantities use `Plottable.numeric`. A channel over a type with
  * no instance is a compile error, so you cannot accidentally plot a `Boolean` or an arbitrary class.
  *
  * `kind` selects the scale family (`Scale.Kind`). `toDomain` projects a value into the scale's native domain
  * coordinate: `Present(d)` for a valid domain point, `Absent` for a value that must be SKIPPED and contribute
  * nothing to the extent (used by `Plottable[Maybe[A]]` for `Absent` inputs). `label` returns the tick or
  * legend text for a value.
  */
trait Plottable[A]:
    def kind: Scale.Kind
    def toDomain(a: A): Maybe[Domain]
    def label(a: A): String
end Plottable

/** Companion object with cached built-in `Plottable` instances and derivation utilities.
  *
  * All built-in instances are `val`s (never `inline`), so each is a single shared object; there is no per-call
  * duplication. Enum instances are produced by `derived`, which uses a thread-safe cache (keyed on the
  * comma-joined label string) so that any two summons for the same enum type return the same object regardless
  * of call site, compilation unit, or JVM class loader.
  *
  * The `enumCache` field is a `ConcurrentHashMap`, which is thread-safe by contract. Values are computed once
  * and never mutated after insertion, and `computeIfAbsent` provides the atomic read-or-create semantic without
  * requiring explicit locks at the call site.
  */
object Plottable:

    given int: Plottable[Int] = continuous(_.toDouble, _.toString)

    given long: Plottable[Long] = continuous(_.toDouble, _.toString)

    given double: Plottable[Double] = continuous(identity, NumberFormat.double)

    given string: Plottable[String] = categorical(identity)

    given instant: Plottable[Instant] = temporal(i => i.toJava.toEpochMilli, i => i.toJava.toString)

    /** `Plottable[Maybe[A]]` projects only `Present` values; `Absent` returns `Absent` so the extent-folding
      * layer skips it entirely.
      *
      * The `kind` is identical to the inner `Plottable[A]` kind: a `Maybe[Int]` channel is still a Linear
      * channel; a `Maybe[String]` channel is still a Band channel. An all-`Absent` column produces an empty
      * extent (no domain contributions at all).
      */
    given maybe[A](using inner: Plottable[A]): Plottable[Maybe[A]] =
        new Plottable[Maybe[A]]:
            def kind: Scale.Kind = inner.kind
            def toDomain(a: Maybe[A]): Maybe[Domain] = a match
                case Present(v) => inner.toDomain(v)
                case Absent     => Absent
            def label(a: Maybe[A]): String = a match
                case Present(v) => inner.label(v)
                case Absent     => ""

    /** Derives a linear `Plottable` for an opaque numeric quantity with an upper `<: Double` bound.
      *
      * The underlying `double` instance is reused directly; the cast is sound because the `<: Double` bound
      * guarantees that `A` is a `Double` at runtime (the opaque alias is erased).
      *
      * This is the one `asInstanceOf` in the charting layer.
      * // Unsafe: sound only because `A <: Double` guarantees the erased runtime type is Double.
      */
    def numeric[A <: Double]: Plottable[A] =
        // Unsafe: sound only because A <: Double guarantees the erased runtime type is Double.
        double.asInstanceOf[Plottable[A]]

    /** Derives a band/ordinal `Plottable` for an enum from its `Mirror.SumOf`.
      *
      * The `inline given` surface reifies the literal label tuple (which must be done inline) and then looks
      * up or inserts an entry in `enumCache` keyed on the comma-joined label string. The heavy allocation
      * (`new Plottable[A]`) is performed at most once per label set, regardless of how many call sites summon
      * the instance and regardless of compilation unit or class loader ordering.
      *
      * Two `summon[Plottable[E]]` calls for the same enum `E` from ANY call site return the same cached
      * object (reference equal), because the cache lookup uses `computeIfAbsent` which is atomic.
      */
    inline given derived[A](using m: Mirror.SumOf[A]): Plottable[A] =
        val labelTuple = constValueTuple[m.MirroredElemLabels]
        cachedDeriveEnum[A](labelTuple)

    // Unsafe: ConcurrentHashMap is shared mutable state accessed from multiple threads.
    // Safe because ConcurrentHashMap is thread-safe by contract, values are immutable after
    // insertion, and computeIfAbsent provides atomic read-or-create semantics.
    private val enumCache: ConcurrentHashMap[String, Plottable[?]] =
        new ConcurrentHashMap[String, Plottable[?]]()

    private def cachedDeriveEnum[A](labelTuple: Tuple): Plottable[A] =
        val labels: Chunk[String] = tupleToChunk(labelTuple)
        val cacheKey              = labels.toSeq.mkString(",")
        // Unsafe: the cast from Plottable[?] to Plottable[A] is safe because the cache is keyed
        // on the unique label string which uniquely identifies the enum type A.
        enumCache
            .computeIfAbsent(cacheKey, _ => deriveEnum[A](labels))
            .asInstanceOf[Plottable[A]]
    end cachedDeriveEnum

    private def deriveEnum[A](labels: Chunk[String]): Plottable[A] =
        new Plottable[A]:
            def kind: Scale.Kind = Scale.Kind.Band
            def toDomain(a: A): Maybe[Domain] =
                // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                Present(Domain.Category(if idx >= 0 && idx < labels.size then labels(idx) else idx.toString))
            end toDomain
            def label(a: A): String =
                // Unsafe: sound because derivation is gated on Mirror.SumOf[A], guaranteeing A is a
                // scala.reflect.Enum whose ordinal is in range of the mirrored element labels.
                val idx = a.asInstanceOf[scala.reflect.Enum].ordinal
                if idx >= 0 && idx < labels.size then labels(idx) else idx.toString
            end label
        end new
    end deriveEnum

    private def tupleToChunk(t: Tuple): Chunk[String] =
        @scala.annotation.tailrec
        def loop(remaining: Tuple, acc: Chunk[String]): Chunk[String] =
            remaining match
                case _: EmptyTuple => acc
                case h *: tail =>
                    loop(tail, acc.append(h.asInstanceOf[String]))
        loop(t, Chunk.empty)
    end tupleToChunk

    private def continuous[A](toD: A => Double, lbl: A => String): Plottable[A] =
        new Plottable[A]:
            def kind: Scale.Kind              = Scale.Kind.Linear
            def toDomain(a: A): Maybe[Domain] = Present(Domain.Continuous(toD(a)))
            def label(a: A): String           = lbl(a)

    private def categorical[A](lbl: A => String): Plottable[A] =
        new Plottable[A]:
            def kind: Scale.Kind              = Scale.Kind.Band
            def toDomain(a: A): Maybe[Domain] = Present(Domain.Category(lbl(a)))
            def label(a: A): String           = lbl(a)

    private def temporal[A](toMillis: A => Long, lbl: A => String): Plottable[A] =
        new Plottable[A]:
            def kind: Scale.Kind              = Scale.Kind.Time
            def toDomain(a: A): Maybe[Domain] = Present(Domain.Temporal(toMillis(a)))
            def label(a: A): String           = lbl(a)

end Plottable
