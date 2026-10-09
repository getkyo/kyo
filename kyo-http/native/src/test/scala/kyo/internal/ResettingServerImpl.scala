package kyo.internal

import kyo.*
import kyo.net.internal.posix.ResettingPosixServer

private[kyo] trait ResettingServerImpl extends ResettingServer:

    def resettingServer(response: Array[Byte], reset: Latch)(using Frame): Int < (Async & Scope) =
        Sync.Unsafe.defer {
            val (serverFd, port) = ResettingPosixServer.listen()
            val serve            = ResettingPosixServer.serveHead(serverFd, response).map { fd =>
                reset.await.andThen(Sync.Unsafe.defer(ResettingPosixServer.reset(fd)))
            }
            Scope.ensure(Sync.Unsafe.defer(ResettingPosixServer.close(serverFd))).andThen(Fiber.init(serve)).andThen(port)
        }

end ResettingServerImpl
