package kyo.compat

import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Return
import com.twitter.util.Throw
import scala.reflect.ClassTag

/** Backed by com.twitter.util.Promise[A]. poll narrows failures back to E via ClassTag; untyped failures are re-thrown. */
opaque type CPromise[A] = Promise[A]

object CPromise:

    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(new Promise[A]())

    inline def lift[A](inline u: Promise[A]): CPromise[A] = u

    extension [A](self: CPromise[A])

        inline def lower: Promise[A] = self

        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.defer(self.updateIfEmpty(Return(a)))

        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.defer(self.updateIfEmpty(Throw(e)))

        inline def get: CIO[A] =
            CIO.lift(self)

        inline def poll: CIO[Option[scala.util.Try[A]]] =
            CIO.defer(self.poll match
                case None            => None
                case Some(Return(a)) => Some(scala.util.Success(a))
                case Some(Throw(t))  => Some(scala.util.Failure(t)))

        inline def done: CIO[Boolean] =
            CIO.defer(self.isDefined)

    end extension

end CPromise
