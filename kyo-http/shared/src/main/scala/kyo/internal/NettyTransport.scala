package kyo.internal

import io.netty.channel.IoHandlerFactory
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kyo.Maybe
import kyo.Result

private[kyo] object NettyTransport:

    lazy val ioHandlerFactory: IoHandlerFactory = selected._1

    lazy val socketChannelClass: Class[? <: SocketChannel] = selected._2

    lazy val serverSocketChannelClass: Class[? <: ServerSocketChannel] = selected._3

    /** True if using Linux epoll transport */
    lazy val isEpoll: Boolean =
        serverSocketChannelClass.getName.startsWith("io.netty.channel.epoll.")

    /** True if using macOS kqueue transport */
    lazy val isKqueue: Boolean =
        serverSocketChannelClass.getName.startsWith("io.netty.channel.kqueue.")

    /** True if using native transport (epoll or kqueue) */
    lazy val isNative: Boolean = isEpoll || isKqueue

    /** TCP_FASTOPEN option, if available (Linux epoll only) */
    private lazy val tcpFastOpenOption: Maybe[io.netty.channel.ChannelOption[Integer]] =
        if isEpoll then
            try
                val optionClass = Class.forName("io.netty.channel.epoll.EpollChannelOption")
                Maybe(optionClass.getField("TCP_FASTOPEN").get(null).asInstanceOf[io.netty.channel.ChannelOption[Integer]])
            catch
                case _: ClassNotFoundException | _: NoSuchFieldException => Maybe.empty
        else Maybe.empty

    /** Applies TCP Fast Open to the server bootstrap if supported (Linux epoll only). No-op on other platforms. */
    def applyTcpFastOpen(bootstrap: io.netty.bootstrap.ServerBootstrap, backlog: Int): Unit =
        tcpFastOpenOption.foreach { opt =>
            val _ = bootstrap.option(opt, Integer.valueOf(backlog))
        }

    private lazy val selected: (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel]) =
        tryEpoll().orElse(tryKQueue()).getOrElse(nio())

    private def nio(): (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel]) =
        (NioIoHandler.newFactory(), classOf[NioSocketChannel], classOf[NioServerSocketChannel])

    private def tryEpoll(): Result[Throwable, (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel])] =
        Result.catching[Throwable] {
            val epollClass  = Class.forName("io.netty.channel.epoll.Epoll")
            val isAvailable = epollClass.getMethod("isAvailable").invoke(null).asInstanceOf[Boolean]
            if isAvailable then
                val handlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler")
                val factory      = handlerClass.getMethod("newFactory").invoke(null).asInstanceOf[IoHandlerFactory]
                val socketClass  = Class.forName("io.netty.channel.epoll.EpollSocketChannel").asInstanceOf[Class[? <: SocketChannel]]
                val serverClass =
                    Class.forName("io.netty.channel.epoll.EpollServerSocketChannel").asInstanceOf[Class[? <: ServerSocketChannel]]
                (factory, socketClass, serverClass)
            else
                throw new RuntimeException("Epoll not available")
            end if
        }

    private def tryKQueue(): Result[Throwable, (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel])] =
        Result.catching[Throwable] {
            val kqueueClass = Class.forName("io.netty.channel.kqueue.KQueue")
            val isAvailable = kqueueClass.getMethod("isAvailable").invoke(null).asInstanceOf[Boolean]
            if isAvailable then
                val handlerClass = Class.forName("io.netty.channel.kqueue.KQueueIoHandler")
                val factory      = handlerClass.getMethod("newFactory").invoke(null).asInstanceOf[IoHandlerFactory]
                val socketClass  = Class.forName("io.netty.channel.kqueue.KQueueSocketChannel").asInstanceOf[Class[? <: SocketChannel]]
                val serverClass =
                    Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel").asInstanceOf[Class[? <: ServerSocketChannel]]
                (factory, socketClass, serverClass)
            else
                throw new RuntimeException("KQueue not available")
            end if
        }

end NettyTransport
