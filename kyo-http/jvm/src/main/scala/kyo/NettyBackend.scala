package kyo

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.flush.FlushConsolidationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.internal.HttpServerHandler
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil

/** JVM backend using Netty 4.2 for HTTP transport. */
object NettyBackend extends Backend:

    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory =
        val threadFactory = new DefaultThreadFactory("kyo-http", daemon)
        val workerGroup   = new MultiThreadIoEventLoopGroup(threadFactory, NettyTransport.ioHandlerFactory)
        val sslContext    = SslContextBuilder.forClient().build()
        val bootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.socketChannelClass)

        new Backend.ConnectionFactory:

            def connect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
                Frame
            ): Backend.Connection < (Async & Abort[HttpError]) =
                val b = bootstrap.clone()
                    .remoteAddress(new InetSocketAddress(host, port))
                    .handler(new ChannelInitializer[SocketChannel]:
                        override def initChannel(ch: SocketChannel): Unit =
                            val pipeline = ch.pipeline()
                            if ssl then
                                discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port)))
                            discard(pipeline.addLast("codec", new HttpClientCodec())))
                connectTimeout.foreach { timeout =>
                    discard(b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(timeout.toMillis.toInt)))
                }
                val connectFuture = b.connect()
                Sync.Unsafe {
                    val p = Promise.Unsafe.init[Backend.Connection, Abort[HttpError]]()
                    connectFuture.addListener { (future: ChannelFuture) =>
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then
                            val closed = AtomicBoolean.Unsafe.init(false).safe
                            discard(p.complete(Result.succeed(
                                new NettyConnection(future.channel(), host, port, maxResponseSizeBytes, closed)
                            )))
                        else
                            discard(p.complete(Result.fail(
                                HttpError.fromThrowable(future.cause(), host, port)
                            )))
                        end if
                    }
                    // Bridge Kyo fiber interruption to Netty: if the fiber is cancelled,
                    // attempt to cancel the in-flight connect. Best-effort — no-op if
                    // the connection already completed.
                    p.onComplete(_ => discard(connectFuture.cancel(true)))
                    p.safe.get
                }
            end connect

            def close(gracePeriod: Duration)(using Frame): Unit < Async =
                val graceMs = gracePeriod.toMillis
                NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
        end new
    end connectionFactory

    def server(
        port: Int,
        host: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        tcpFastOpen: Boolean,
        flushConsolidationLimit: Int,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server < Async =
        Sync.defer {
            // Boss group accepts connections (1 thread), worker group handles I/O
            val bossGroup   = new MultiThreadIoEventLoopGroup(1, NettyTransport.ioHandlerFactory)
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)

            val bootstrap = new ServerBootstrap()
            discard {
                bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NettyTransport.serverSocketChannelClass)
                    // Pipeline per connection: flush consolidation → HTTP codec → our state-machine handler
                    .childHandler(new ChannelInitializer[SocketChannel]:
                        override def initChannel(ch: SocketChannel): Unit =
                            val pipeline = ch.pipeline()
                            discard(pipeline.addLast(new FlushConsolidationHandler(
                                flushConsolidationLimit,
                                true
                            )))
                            discard(pipeline.addLast(new HttpServerCodec()))
                            discard(pipeline.addLast(new HttpServerHandler(
                                handler,
                                maxContentLength
                            ))))
                    .option(ChannelOption.SO_BACKLOG, Integer.valueOf(backlog))
                    .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(keepAlive))
                    .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
            }
            if tcpFastOpen then
                NettyTransport.applyTcpFastOpen(bootstrap, backlog)

            val bindFuture = bootstrap.bind(host, port)

            NettyUtil.continue(bindFuture) { channel =>
                val address = channel.localAddress().asInstanceOf[InetSocketAddress]
                new Backend.Server:
                    def port: Int    = address.getPort
                    def host: String = address.getHostString
                    // Shutdown order: stop accepting → drain boss → drain workers
                    def close(gracePeriod: Duration)(using Frame): Unit < Async =
                        val graceMs = gracePeriod.toMillis
                        NettyUtil.continue(channel.close()) { _ =>
                            NettyUtil.continue(bossGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)) { _ =>
                                NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
                            }
                        }
                    end close
                    def await(using Frame): Unit < Async =
                        NettyUtil.await(channel.closeFuture())
                end new
            }
        }
    end server

end NettyBackend
