package kyo.test

import java.util.concurrent.ConcurrentHashMap
import kyo.*

// A Fun[A, B] is a referentially transparent cached function from A to B that does not involve asynchronous effects.
final case class Fun[-A, +B] private (private val f: A => B, private val hash: A => Int) extends (A => B):

    private val cache = new ConcurrentHashMap[Int, (A, B)]()

    def apply(a: A): B =
        val key   = hash(a)
        val entry = cache.get(key)
        if entry != null then entry._2
        else
            val result = f(a)
            cache.put(key, (a, result))
            result
        end if
    end apply

    override def toString: String =
        import scala.jdk.CollectionConverters.*
        val mappings = cache.values().asScala.map { case (a, b) => s"$a -> $b" }.toList
        s"Fun(${mappings.mkString(", ")})"
    end toString
end Fun

object Fun:

    /** Constructs a new Fun from an effectful function f that does not involve asynchronous effects. The effect f has type B < Env[R] and
      * is run synchronously using the provided environment.
      */
    def make[R, A, B](f: A => B < Env[R]): (Fun[A, B] < Env[R]) =
        makeHash(f)(_.hashCode)

    /** Constructs a new Fun from an effectful function with a custom hashing function.
      */
    def makeHash[R, A, B](f: A => B < Env[R])(hash: A => Int): (Fun[A, B] < Env[R]) =
        Env.get[R].map { r =>
            Fun[A, B](
                a => f(a).pipe(Env.run(r)).eval, // run the effect using the provided environment and evaluate to get a pure value
                hash
            )
        }

    /** Constructs a new Fun from a pure function.
      */
    def fromFunction[A, B](f: A => B): Fun[A, B] =
        Fun(f, _.hashCode)
end Fun
