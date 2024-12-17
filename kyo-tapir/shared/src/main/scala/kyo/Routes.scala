package kyo

import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyKyoServer
import sttp.tapir.server.netty.NettyKyoServerBinding

/** Represents a single route with a server endpoint. */
case class Route(endpoint: ServerEndpoint[Any, KyoSttpMonad.M]) extends AnyVal

/** Represents an effectful collection of routes with asynchronous capabilities. */
opaque type Routes <: (Emit[Route] & Async) = Emit[Route] & Async

object Routes:

    /** Runs the routes using the default NettyKyoServer.
      *
      * @param v
      *   The routes to run
      * @return
      *   A NettyKyoServerBinding wrapped in an asynchronous effect
      */
    def run[A, S](v: Unit < (Routes & S))(using Frame): NettyKyoServerBinding < (Async & S) =
        run[A, S](NettyKyoServer())(v)

    /** Runs the routes using a specified NettyKyoServer.
      *
      * @param server
      *   The NettyKyoServer to use
      * @param v
      *   The routes to run
      * @return
      *   A NettyKyoServerBinding wrapped in an asynchronous effect
      */
    def run[A, S](server: NettyKyoServer)(v: Unit < (Routes & S))(using Frame): NettyKyoServerBinding < (Async & S) =
        Emit.run[Route].apply[Unit, Async & S](v).map { (routes, _) =>
            IO(server.addEndpoints(routes.toSeq.map(_.endpoint).toList).start()): NettyKyoServerBinding < (Async & S)
        }
    end run

    /** Runs the routes using a specified NettyKyoServer.
      *
      * @param server
      *   The NettyKyoServer to use
      * @param v
      *   The routes to run
      * @param update
      *   A function that allows updates to the underlying server endpoints before starting the server
      * @return
      *   A NettyKyoServerBinding wrapped in an asynchronous effect
      */
    def run[A, S](server: NettyKyoServer)(
        v: Unit < (Routes & S)
    )(update: List[ServerEndpoint[Any, M]] => List[ServerEndpoint[Any, M]])(using Frame): NettyKyoServerBinding < (Async & S) =
        Emit.run[Route].apply[Unit, Async & S](v).map { (routes, _) =>
            IO(server.addEndpoints(update(routes.toSeq.map(_.endpoint).toList)).start()): NettyKyoServerBinding < (Async & S)
        }
    end run

    /** Adds a new route to the collection.
      *
      * @param e
      *   The endpoint to add
      * @param f
      *   The function to handle the endpoint logic
      * @return
      *   Unit wrapped in Routes effect
      */
    def add[A: Tag, I, E: SafeClassTag, O: Flat](e: Endpoint[A, I, E, O, Any])(
        f: I => O < (Async & Env[A] & Abort[E])
    )(using Frame): Unit < Routes =
        Emit(
            Route(
                e.serverSecurityLogic[A, KyoSttpMonad.M](a => Right(a)).serverLogic((a: A) =>
                    (i: I) =>
                        Abort.run[E](Env.run(a)(f(i))).map {
                            case Result.Success(v) => Right(v)
                            case Result.Fail(e)    => Left(e)
                            case Result.Panic(ex)  => throw ex
                        }
                )
            )
        ).unit

    /** Adds a new route to the collection, starting from a PublicEndpoint.
      *
      * @param e
      *   A function to create an Endpoint from a PublicEndpoint
      * @param f
      *   The function to handle the endpoint logic
      * @return
      *   Unit wrapped in Routes effect
      */
    def add[A: Tag, I, E: SafeClassTag, O: Flat](
        e: PublicEndpoint[Unit, Unit, Unit, Any] => Endpoint[A, I, E, O, Any]
    )(
        f: I => O < (Async & Env[A] & Abort[E])
    )(using Frame): Unit < Routes =
        add(e(endpoint))(f)

    /** Collects multiple route initializations into a single Routes effect.
      *
      * @param init
      *   A sequence of route initializations
      * @return
      *   Unit wrapped in Routes effect
      */
    def collect(init: (Unit < Routes)*)(using Frame): Unit < Routes =
        Kyo.collect(init).unit

end Routes
