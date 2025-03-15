package kyo.internal

type ForSome[F[_]] = ForSome.Type[F]
object ForSome:
    type Type[F[_]] <: { type A }

    inline def apply[F[_], A](f: F[A]): Type[F] = f.asInstanceOf[Type[F]]

    extension [F[_]](value: Type[F])
        inline def unwrap: F[value.A] = value.asInstanceOf[F[value.A]]

    inline def of[F[_]]: OfOps[F] = new OfOps[F](())
    class OfOps[F[_]](dummy: Unit) extends AnyVal:
        inline def apply[A](f: F[A]): Type[F] = ForSome(f)
end ForSome

type ForSome2[F[_, _]] = ForSome2.Type[F]
object ForSome2:
    type Type[F[_, _]] <: { type A1; type A2 }

    inline def apply[F[_, _], A1, A2](f: F[A1, A2]): Type[F] = f.asInstanceOf[Type[F]]

    inline def unapply[F[_, _]](value: Type[F]): F[value.A1, value.A2] = value.asInstanceOf[F[value.A1, value.A2]]

    extension [F[_, _]](value: Type[F])
        inline def unwrap: F[value.A1, value.A2] = unapply(value)

    inline def of[F[_, _]]: OfOps[F] = new OfOps[F](())

    class OfOps[F[_, _]](dummy: Unit) extends AnyVal:
        def apply[A1, A2](f: F[A1, A2]): Type[F] = ForSome2(f)
end ForSome2
