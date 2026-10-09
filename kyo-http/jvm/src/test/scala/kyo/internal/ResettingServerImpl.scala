package kyo.internal

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kyo.*

private[kyo] trait ResettingServerImpl extends ResettingServer:

    def resettingServer(response: Array[Byte], reset: Latch)(using Frame): Int < (Async & Scope) =
        Sync.Unsafe.defer {
            val listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val written  = Promise.Unsafe.init[Socket, Any]()
            // The accept and the reads block, so they run on a thread of their own rather than on a scheduler carrier; closing the listener
            // at the scope's end unblocks an accept no client made.
            val serving = new Thread(() =>
                try
                    val socket = listener.accept()
                    val in     = socket.getInputStream
                    val head   = new java.lang.StringBuilder
                    while head.indexOf("\r\n\r\n") < 0 do
                        val b = in.read()
                        if b < 0 then throw new java.io.EOFException("the client closed before its request head ended")
                        discard(head.append(b.toChar))
                    end while
                    socket.getOutputStream.write(response)
                    socket.getOutputStream.flush()
                    written.completeDiscard(Result.succeed(socket))
                catch case e: Throwable => written.completeDiscard(Result.panic(e))
            )
            serving.setDaemon(true)
            serving.start()
            val serve = written.safe.get.map { socket =>
                reset.await.andThen(Sync.defer {
                    socket.setSoLinger(true, 0)
                    socket.close()
                })
            }
            Scope.ensure(Sync.defer(listener.close())).andThen(Fiber.init(serve)).andThen(listener.getLocalPort)
        }

end ResettingServerImpl
