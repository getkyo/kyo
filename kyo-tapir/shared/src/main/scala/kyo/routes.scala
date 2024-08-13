package kyo

import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import scala.reflect.ClassTag
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyKyoServer
import sttp.tapir.server.netty.NettyKyoServerBinding

case class Route(endpoint: ServerEndpoint[Any, KyoSttpMonad.M]) extends AnyVal

type Routes >: Routes.Effects <: Routes.Effects

object Routes:

    type Effects = Emit[Route] & Async

    def run[A, S](v: Unit < (Routes & S))(using Frame): NettyKyoServerBinding < (Async & S) =
        run[A, S](NettyKyoServer())(v)

    def run[A, S](server: NettyKyoServer)(v: Unit < (Routes & S))(using Frame): NettyKyoServerBinding < (Async & S) =
        Emit.run[Route].apply[Unit, Async & S](v).map { (routes, _) =>
            IO(server.addEndpoints(routes.toSeq.map(_.endpoint).toList).start()): NettyKyoServerBinding < (Async & S)
        }
    end run

    def add[A: Tag, I, E: Tag: ClassTag, O: Flat](e: Endpoint[A, I, E, O, Any])(
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

    def add[A: Tag, I, E: Tag: ClassTag, O: Flat](
        e: PublicEndpoint[Unit, Unit, Unit, Any] => Endpoint[A, I, E, O, Any]
    )(
        f: I => O < (Async & Env[A] & Abort[E])
    )(using Frame): Unit < Routes =
        add(e(endpoint))(f)

    def collect(init: (Unit < Routes)*)(using Frame): Unit < Routes =
        Kyo.seq.collect(init).unit

end Routes
