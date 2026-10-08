package kyo.bench.arena

import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.VerticleBase
import io.vertx.core.Vertx
import io.vertx.core.http.Http2Settings
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

object TestHttpServer:

    // Only a catastrophic guard: readiness is the child's own signal, and a child that dies fails the wait at once. A cold forked JVM
    // on a loaded windows-arm64 runner took more than 10 s to deploy, so no budget sized to a normal startup is safe.
    private val startupGuardMinutes = 5L

    def log(port: Int, msg: String) = println(s"TestHttpServer(port=$port): $msg")

    // The child prints this only after every verticle instance has bound its port, so a parent that sees it can connect.
    private def readyLine(port: Int) = s"TestHttpServer(port=$port): listening"

    // Each HTTP benchmark forks its own server process; a hardcoded port made those
    // processes collide (a second bind silently failed, then a process exit left the
    // survivors' clients with Connection-refused). Allocate a fresh ephemeral port per
    // server instead. Probed on the loopback address, where the server listens: with SO_REUSEADDR a wildcard probe can
    // return a port another process listens on at 127.0.0.1, and the client's connect then reaches that listener.
    private def freePort(): Int =
        val socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        try socket.getLocalPort
        finally socket.close()
    end freePort

    def start(concurrency: Int): String = start(concurrency, "kyo.bench.arena.TestHttpServer")

    private[arena] def start(concurrency: Int, mainClass: String): String =
        val port      = freePort()
        val javaBin   = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val command   =
            List(javaBin, "-cp", classpath, mainClass, concurrency.toString, port.toString)
        val builder = new ProcessBuilder(command*)
        log(port, "forking")
        val process = builder.start()
        Runtime.getRuntime().addShutdownHook(new Thread:
            override def run(): Unit =
                log(port, "stopping")
                process.destroy())
        val ready = new CompletableFuture[Unit]
        redirect(process.getInputStream, System.out, line => if line == readyLine(port) then discard(ready.complete(())))
        redirect(process.getErrorStream, System.err, _ => ())
        discard(process.onExit().thenAccept(exited =>
            discard(ready.completeExceptionally(
                new RuntimeException(s"Server failed to start: the server process exited with code ${exited.exitValue} before listening")
            ))
        ))
        try ready.get(startupGuardMinutes, TimeUnit.MINUTES)
        catch
            case e: ExecutionException =>
                throw e.getCause
            case _: TimeoutException =>
                process.destroy()
                throw new RuntimeException(s"Server failed to start: no listening report within $startupGuardMinutes minutes")
        end try
        log(port, "ready")
        s"http://127.0.0.1:$port/ping"
    end start

    private def discard[A](a: A): Unit = ()

    def redirect(inputStream: InputStream, outputStream: PrintStream, onLine: String => Unit): Unit =
        val runnable =
            new Runnable:
                def run(): Unit =
                    val reader       = new BufferedReader(new InputStreamReader(inputStream))
                    val writer       = new PrintWriter(outputStream)
                    var line: String = null
                    while { line = reader.readLine(); line != null } do
                        writer.println(s"[TestHttpServer] $line")
                        writer.flush()
                        onLine(line)
                    end while
                end run
        val thread = new Thread(runnable)
        thread.setDaemon(true)
        thread.start()
    end redirect

    class PingVerticle extends VerticleBase:
        // Returning the listen future makes deployment succeed only once this instance has bound the port.
        override def start(): Future[?] =
            val port          = config().getInteger("port")
            val serverOptions = new HttpServerOptions()
                .setMaxInitialLineLength(8192)
                .setMaxHeaderSize(8192)
                .setMaxChunkSize(8192)
                .setIdleTimeout(0)
                .setTcpKeepAlive(true)
                .setInitialSettings(
                    new Http2Settings().setMaxConcurrentStreams(1000)
                )

            val server: HttpServer = vertx.createHttpServer(serverOptions)
            server.requestHandler { request =>
                val response = request.response()
                try
                    response.setStatusCode(200)
                    response.putHeader("Content-Type", "text/plain")
                    response.end("pong")
                catch
                    case ex if NonFatal(ex) =>
                        log(port, s"Error handling request: ${ex.getClass}")
                        try
                            response.reset(500)
                        catch
                            case e if NonFatal(e) =>
                                log(port, s"Error closing response: ${e.getClass}")
                        end try
                end try
                ()
            }.listen(port, "127.0.0.1")
        end start
    end PingVerticle

    def main(args: Array[String]): Unit =
        val concurrency = args(0).toInt
        val port        = args(1).toInt
        log(port, "starting")
        val vertx   = Vertx.vertx()
        val options =
            new DeploymentOptions()
                .setInstances(concurrency)
                .setConfig(new JsonObject().put("port", port))
        vertx.deployVerticle(classOf[PingVerticle], options).andThen { result =>
            if result.succeeded then
                println(readyLine(port))
                java.lang.System.out.flush()
            else
                log(port, s"Deployment failed: ${result.cause}")
                java.lang.System.exit(1)
        }
        try
            java.util.concurrent.locks.LockSupport.park()
        finally
            log(port, "stopped")
        end try
    end main

end TestHttpServer
