package kyo

import izumi.reflect.*
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*
import scala.reflect.ClassTag
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyKyoServer
import sttp.tapir.server.netty.NettyKyoServerBinding

type Route = ServerEndpoint[Any, KyoSttpMonad.M]

type Routes >: Routes.Effects <: Routes.Effects

object Routes:

    type Effects = Sums[List[Route]] & Fibers

    private val sums = Sums[List[Route]]

    def run[T, S](v: Unit < (Routes & S)): NettyKyoServerBinding < (Fibers & S) =
        run[T, S](NettyKyoServer())(v)

    def run[T, S](server: NettyKyoServer)(v: Unit < (Routes & S))
        : NettyKyoServerBinding < (Fibers & S) =
        sums.run[NettyKyoServerBinding, Fibers & S] {
            v.andThen(sums.get.map(server.addEndpoints(_)).map(_.start()))
        }.map(_._1)

    def add[S, A: Tag, I, E: Tag: ClassTag, O: Flat](e: Endpoint[A, I, E, O, Any])(
        f: I => O < (Fibers & Envs[A] & Aborts[E])
    ): Unit < Routes =
        sums.add(List(
            e.serverSecurityLogic[A, KyoSttpMonad.M](a => Right(a)).serverLogic(a =>
                i => Aborts[E].run(Envs[A].run(a)(f(i)))
            )
        )).unit
    end add

    def add[S, A: Tag, I, E: Tag: ClassTag, O: Flat](
        e: PublicEndpoint[Unit, Unit, Unit, Any] => Endpoint[A, I, E, O, Any]
    )(
        f: I => O < (Fibers & Envs[A] & Aborts[E])
    ): Unit < Routes =
        add(e(endpoint))(f)

    def collect(init: (Unit < Routes)*): Unit < Routes =
        Seqs.collect(init).unit

end Routes
