package kyo

import kyo.server.*

import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad.*

object routes:

    type Route[+T] = ServerEndpoint[Any, KyoSttpMonad.M]

    type Routes >: Routes.Effects <: Routes.Effects

    object Routes:

        type Effects = Sums[List[Route[Any]]] & Fibers

        private val sums = Sums[List[Route[Any]]]

        def run[T, S](v: Unit < (Routes & S)): NettyKyoServerBinding < (Fibers & S) =
            run[T, S](NettyKyoServer())(v)

        def run[T, S](server: NettyKyoServer)(v: Unit < (Routes & S))
            : NettyKyoServerBinding < (Fibers & S) =
            sums.run[NettyKyoServerBinding, Fibers & S] {
                v.andThen(sums.get.map(server.addEndpoints(_)).map(_.start()))
            }.map(_._1)

        def add[T, U, E, S](e: Endpoint[Unit, T, Unit, U, Unit])(
            f: T => U < Fibers
        ): Unit < Routes =
            sums.add(List(
                e.serverLogic[KyoSttpMonad.M](f(_).map(Right(_))).asInstanceOf[Route[Any]]
            )).unit

        def add[T, U, S](
            e: PublicEndpoint[Unit, Unit, Unit, Any] => Endpoint[Unit, T, Unit, U, Any]
        )(
            f: T => U < Fibers
        ): Unit < Routes =
            add(e(endpoint))(f)
    end Routes
end routes
