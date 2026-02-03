package kyo.internal

import io.netty.channel.IoHandlerFactory
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import scala.util.Try

private[kyo] object NettyTransport:

    lazy val ioHandlerFactory: IoHandlerFactory = selected._1

    lazy val socketChannelClass: Class[? <: SocketChannel] = selected._2

    lazy val serverSocketChannelClass: Class[? <: ServerSocketChannel] = selected._3

    private lazy val selected: (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel]) =
        tryEpoll().orElse(tryKQueue()).getOrElse(nio())

    private def nio(): (IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel]) =
        (NioIoHandler.newFactory(), classOf[NioSocketChannel], classOf[NioServerSocketChannel])

    private def tryEpoll(): Option[(IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel])] =
        Try {
            val epollClass  = Class.forName("io.netty.channel.epoll.Epoll")
            val isAvailable = epollClass.getMethod("isAvailable").invoke(null).asInstanceOf[Boolean]
            if isAvailable then
                val handlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler")
                val factory      = handlerClass.getMethod("newFactory").invoke(null).asInstanceOf[IoHandlerFactory]
                val socketClass  = Class.forName("io.netty.channel.epoll.EpollSocketChannel").asInstanceOf[Class[? <: SocketChannel]]
                val serverClass =
                    Class.forName("io.netty.channel.epoll.EpollServerSocketChannel").asInstanceOf[Class[? <: ServerSocketChannel]]
                Some((factory, socketClass, serverClass))
            else None
            end if
        }.toOption.flatten

    private def tryKQueue(): Option[(IoHandlerFactory, Class[? <: SocketChannel], Class[? <: ServerSocketChannel])] =
        Try {
            val kqueueClass = Class.forName("io.netty.channel.kqueue.KQueue")
            val isAvailable = kqueueClass.getMethod("isAvailable").invoke(null).asInstanceOf[Boolean]
            if isAvailable then
                val handlerClass = Class.forName("io.netty.channel.kqueue.KQueueIoHandler")
                val factory      = handlerClass.getMethod("newFactory").invoke(null).asInstanceOf[IoHandlerFactory]
                val socketClass  = Class.forName("io.netty.channel.kqueue.KQueueSocketChannel").asInstanceOf[Class[? <: SocketChannel]]
                val serverClass =
                    Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel").asInstanceOf[Class[? <: ServerSocketChannel]]
                Some((factory, socketClass, serverClass))
            else None
            end if
        }.toOption.flatten

end NettyTransport
