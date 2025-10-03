package kyo.experimental

import kyo.*
import kyo.experimental.Service.internal
import kyo.kernel.ContextEffect
import scala.annotation.implicitNotFound
import scala.collection.immutable.TreeSeqMap

//todo : create a better Structure, ServiceMap[R, Any]

// Based on ContextEffect (which is not an ArrowEffect) - similar to what's used by Env.
// Uses R[Any] <: R[S] because when handled, S will be added to the pending effect set.
opaque type Use[+R[-_]] = ContextEffect[TypeMap[R[Any]]]

// Helper type alias for combining two effect requirements R1 and R2 into a single requirement
infix type &&[+R1[-_], +R2[-_]] = [S] =>> R1[S] & R2[S]

object Use:

    // Helper type used for type erasure
    private[kyo] trait AnyT[-A]

    // Creates a suspended computation that requests and uses a context value R.
    // The R[Use[R]] type ensures proper tracking of the Use effect in the type system,
    // even when lifted into nested contexts like Stream or (A < S1) < S2.
    def use[R[-_]](using frame: Frame)[A, S1](f: R[Use[R]] => A < S1)(using tag: Tag[R[Any]]): A < (Use[R] & S1) =
        ContextEffect.suspendWith(internal.erasedTag[R]): map =>
            f(map.asInstanceOf[TypeMap[R[Any]]].get(using tag))

    // Runs an effectful computation by providing an implementation R[S1].
    // Adds S1 to the pending effect set and provides R[?] to satisfy Use[R].
    def run[R[-_], S1](r: R[S1])[A, S2](a: A < (Use[R] & S2))(using tag: Tag[R[Any]], frame: Frame): A < (S1 & S2) =
        val env: TypeMap[R[Any]] = TypeMap(r.asInstanceOf[R[Any]])
        ContextEffect.handle(internal.erasedTag[R], env, _.union(env))(a)

    // Retrieves the context value of type R within a Use[R] effect context
    def get[R[-_]](using frame: Frame, tag: Tag[R[Any]]): R[Use[R]] < Use[R] = use[R](identity)

    private[kyo] object internal:
        // Internal utilities for type tag handling
        def erasedTag[R[-_]]: Tag[Use[R]] = Tag[Use[AnyT]].asInstanceOf[Tag[Use[R]]]

end Use

// Extension of Use that includes async capability
opaque type UseAsync[+R[-_]] <: (Use[R] & kyo.Async) = ContextEffect[TypeMap[R[Any]]] & kyo.Async

object UseAsync:

    // supported effects for A < UseAsync[SomeService]
    // those effects are what are commonly found in ZIO / CE
    // for example, Var, Emit, Check, ... are not supported for now
    private type SupportedEffects = Async & Abort[Any] & Env[Nothing]

    // Type class instance for isolating async effects
    inline given [R[-_]]: Isolate[UseAsync[R], kyo.Async, UseAsync[R]] =
        Isolate.derive[SupportedEffects, kyo.Async, SupportedEffects].asInstanceOf

    // Async variant of Use.use that includes Async effect
    def use[R[-_]](using frame: Frame)[A, S1](f: R[UseAsync[R]] => A < S1)(using tag: Tag[R[Any]]): A < (UseAsync[R] & S1) =
        Use.use(f)

    // Async variant of Use.run that be used in an Async context
    def run[R[-_], S1](r: R[S1])(using
        frame: Frame,
        @implicitNotFound(
            "UseAsync.run requires the service effect S to be limited to Async, Abort[Error & ...], Env[Resource & ...]. Provided: ${S1}. " +
                "\nHint: limit your service effect types to those effects, or use Use.run for non-async services."
        )
        ev: SupportedEffects <:< S1
    )[A, S2](a: A < (UseAsync[R] & S2))(using tap: Tag[R[Any]]): A < (S1 & S2) =
        Use.run(r)(a.asInstanceOf[A < (Use[R] & S2)])
    end run

end UseAsync

extension [A, R[-_], S](v: A < (Use[R] & S))
    def toUseAsync: A < (UseAsync[R] & S) = v

opaque type HKTMap[+R[-_], -S] = TreeSeqMap[Tag[Any], Any]

object HKTMap:

    extension [R[-_], S](self: HKTMap[R, S])

        private inline def fatal[T[-_]](using t: Tag[T[Any]]): Nothing =
            throw new RuntimeException(s"fatal: kyo.experimental.HKTMap of contents [${self.show}] missing value of type: [${t.show}].")

        /** Retrieves a value of higher-kinded type R2 from the HKTMap.
          *
          * @tparam R2
          *   The higher-kinded type to retrieve (must be a supertype of R)
          * @param t
          *   An implicit Tag for type R2[Any]
          * @param ev
          *   Evidence that R[Any] is a subtype of R2[Any]
          * @return
          *   The value of type R2[S]
          * @throws RuntimeException
          *   if the value is not found
          */
        def get[R2[-_]](using t: Tag[R2[Any]], ev: R[Any] <:< R2[Any]): R2[S] =
            def search: Any =
                val it = self.iterator
                while it.hasNext do
                    val (tag, item) = it.next()
                    if tag <:< t then
                        return item
                end while
                fatal
            end search
            if isEmpty && t =:= Tag[Any] then ().asInstanceOf[R2[S]]
            else self.getOrElse(t.erased, search).asInstanceOf[R2[S]]
        end get

        /** Adds a new HKT-value pair to the HKTMap.
          *
          * @param r2
          *   The HKT instance to add
          * @tparam R2
          *   The higher-kinded type of the value to add
          * @param t
          *   An implicit Tag for type R2[Any]
          * @return
          *   A new HKTMap with the added HKT-value pair
          */
        inline def add[R2[-_]](r2: R2[S])(using inline t: Tag[R2[Any]]): HKTMap[R && R2, S] =
            self.updated(t.erased, r2)

        /** Combines this HKTMap with another HKTMap.
          *
          * @param that
          *   The HKTMap to combine with
          * @tparam R2
          *   The higher-kinded type of values in the other HKTMap
          * @tparam S2
          *   The type parameter of the other HKTMap
          * @return
          *   A new HKTMap containing all HKT-value pairs from both HKTMaps
          */
        inline def union[R2[-_], S2](that: HKTMap[R2, S2]): HKTMap[R && R2, S & S2] =
            self ++ that

        /** Filters the HKTMap to only include HKT-value pairs where the HKT is a subtype of the given type.
          *
          * @tparam R2
          *   The higher-kinded type to filter by (must be a supertype of R)
          * @param t
          *   An implicit Tag for type R2[Any]
          * @return
          *   A new HKTMap containing only the filtered HKT-value pairs
          */
        def prune[R2[-_] >: R](using t: Tag[R2[Any]]): HKTMap[R2, S] =
            if t =:= Tag[Any] then self
            else self.filter { case (tag, _) => tag <:< t }

        /** Returns the number of HKT-value pairs in the HKTMap.
          *
          * @return
          *   The size of the HKTMap
          */
        inline def size: Int = self.size

        /** Checks if the HKTMap is empty.
          *
          * @return
          *   true if the HKTMap is empty, false otherwise
          */
        inline def isEmpty: Boolean = self.isEmpty

        /** Returns a string representation of the HKTMap.
          *
          * @return
          *   A string describing the contents of the HKTMap
          */
        def show: String = self.map { case (tag, value) => s"${tag.show} -> $value" }.toList.sorted.mkString("HKTMap(", ", ", ")")

        private[kyo] inline def <:<[T[-_]](tag: Tag[T[Any]]): Boolean =
            self.keySet.exists(_ <:< tag)
    end extension

    /** An empty HKTMap. */
    val empty: HKTMap[Const[Any], Any] = TreeSeqMap.empty(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with a single HKT-value pair.
      *
      * @param r
      *   The HKT instance to add
      * @tparam R
      *   The higher-kinded type of the value
      * @tparam S
      *   The type parameter of the HKT
      * @param tr
      *   An implicit Tag for type R[Any]
      * @return
      *   A new HKTMap with the single HKT-value pair
      */
    def apply[R[-_], S](r: R[S])(using tr: Tag[R[Any]]): HKTMap[R, S] =
        TreeSeqMap(tr.erased -> r).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with two HKT-value pairs.
      *
      * @param r1
      *   The first HKT instance to add
      * @param r2
      *   The second HKT instance to add
      * @tparam R1
      *   The higher-kinded type of the first value
      * @tparam R2
      *   The higher-kinded type of the second value
      * @tparam S
      *   The type parameter of both HKTs
      * @param tr1
      *   An implicit Tag for type R1[Any]
      * @param tr2
      *   An implicit Tag for type R2[Any]
      * @return
      *   A new HKTMap with the two HKT-value pairs
      */
    def apply[R1[-_], R2[-_], S](r1: R1[S], r2: R2[S])(using tr1: Tag[R1[Any]], tr2: Tag[R2[Any]]): HKTMap[R1 && R2, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with three HKT-value pairs.
      */
    def apply[R1[-_], R2[-_], R3[-_], S](r1: R1[S], r2: R2[S], r3: R3[S])(using
        tr1: Tag[R1[Any]],
        tr2: Tag[R2[Any]],
        tr3: Tag[R3[Any]]
    ): HKTMap[R1 && R2 && R3, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2, tr3.erased -> r3).orderingBy(TreeSeqMap.OrderBy.Modification)

    /** Creates an HKTMap with four HKT-value pairs.
      */
    def apply[R1[-_], R2[-_], R3[-_], R4[-_], S](r1: R1[S], r2: R2[S], r3: R3[S], r4: R4[S])(using
        tr1: Tag[R1[Any]],
        tr2: Tag[R2[Any]],
        tr3: Tag[R3[Any]],
        tr4: Tag[R4[Any]]
    ): HKTMap[R1 && R2 && R3 && R4, S] =
        TreeSeqMap(tr1.erased -> r1, tr2.erased -> r2, tr3.erased -> r3, tr4.erased -> r4).orderingBy(TreeSeqMap.OrderBy.Modification)

end HKTMap

sealed abstract class Service[+R[-_], -S] extends Serializable:
    self =>

    infix def and[R1[-_], S1](service: Service[R1, S1]): Service[R && R1, S & S1] =
        Service.internal.Provide(self, service)

    infix def provide[R1[-_], S1](that: Service[R1, S1 & Use[R]]): Service[R && R1, S & S1] =
        Service.internal.Provide(self, that)

    def run[S1, A](v: A < (S1 & Use[R]))(using frame: Frame): A < (S1 & S) =
        this match
            case internal.Provide(first, second) => ???
            case k0: internal.FromKyo_0[?, ?] =>
                import k0.tag
                k0.v().map(r => Use.run(r)(v))
            case internal.FromKyo_1(v) => ???

end Service

object Service:
    def apply[R[-_], S1, S2](v: => R[S1] < S2)(using Tag[R[Any]], Frame): Service[R, S1 & S2] =
        internal.FromKyo_0[R, S1 & S2](() => v)

    def using[R0[-_]](using
        Frame,
        Tag[R0[Any]]
    )[R1[-_], S1, S2](v: R0[Use[R0]] => R1[S1] < (S2 & Use[R0]))(using Tag[R1[Any]]): Service[R1, Use[R0] & S1 & S2] =
        internal.FromKyo_1[R0, R1, S1 & S2](() => Use.use[R0](v))

    private object internal:
        case class Provide[R0[-_], R1[-_], S0, S1](first: Service[R0, S0], second: Service[R1, S1 & Use[R0]])
            extends Service[R0 && R1, S0 & S1]

        case class FromKyo_0[Out[-_], S](v: () => Out[S] < S)(using val tag: Tag[Out[Any]]) extends Service[Out, S]
        case class FromKyo_1[In[-_], Out[-_], S](v: () => Out[Use[In] & S] < (Use[In] & S))
            extends Service[Out, Use[In] & S]
    end internal
end Service
