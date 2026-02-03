package kyo.internal

import kyo.Test

class NettyTransportTest extends Test:

    "NettyTransport" - {
        "selects native transport for platform" in {
            val os = System.getProperty("os.name").toLowerCase

            val factoryName     = NettyTransport.ioHandlerFactory.getClass.getName
            val socketClassName = NettyTransport.socketChannelClass.getName
            val serverClassName = NettyTransport.serverSocketChannelClass.getName

            info(s"OS: $os")
            info(s"IoHandlerFactory: $factoryName")
            info(s"SocketChannel: $socketClassName")
            info(s"ServerSocketChannel: $serverClassName")

            if os.contains("linux") then
                // On Linux with native deps in test scope, must use Epoll
                assert(factoryName.contains("Epoll"), s"Expected Epoll factory on Linux, got $factoryName")
                assert(socketClassName.contains("Epoll"), s"Expected EpollSocketChannel, got $socketClassName")
                assert(serverClassName.contains("Epoll"), s"Expected EpollServerSocketChannel, got $serverClassName")
            else if os.contains("mac") || os.contains("darwin") then
                // On macOS with native deps in test scope, must use KQueue
                assert(factoryName.contains("KQueue"), s"Expected KQueue factory on macOS, got $factoryName")
                assert(socketClassName.contains("KQueue"), s"Expected KQueueSocketChannel, got $socketClassName")
                assert(serverClassName.contains("KQueue"), s"Expected KQueueServerSocketChannel, got $serverClassName")
            else
                // On other platforms (Windows, etc.), should use NIO
                assert(factoryName.contains("Nio"), s"Expected Nio factory on $os, got $factoryName")
                assert(socketClassName.contains("Nio"), s"Expected NioSocketChannel, got $socketClassName")
                assert(serverClassName.contains("Nio"), s"Expected NioServerSocketChannel, got $serverClassName")
            end if

            succeed
        }

        "all components are consistent" in {
            val factoryName     = NettyTransport.ioHandlerFactory.getClass.getName
            val socketClassName = NettyTransport.socketChannelClass.getName
            val serverClassName = NettyTransport.serverSocketChannelClass.getName

            // Extract transport type from factory name
            val transportType =
                if factoryName.contains("Epoll") then "Epoll"
                else if factoryName.contains("KQueue") then "KQueue"
                else "Nio"

            // All components should use the same transport
            assert(
                socketClassName.contains(transportType),
                s"Socket channel ($socketClassName) doesn't match factory transport ($transportType)"
            )
            assert(
                serverClassName.contains(transportType),
                s"Server socket channel ($serverClassName) doesn't match factory transport ($transportType)"
            )

            succeed
        }
    }

end NettyTransportTest
