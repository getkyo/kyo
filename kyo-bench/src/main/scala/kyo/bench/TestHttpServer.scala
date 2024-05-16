package kyo.bench

import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.PrintWriter
import scala.util.control.NonFatal

object TestHttpServer:
    val port = 8888

    trait StopServer:
        def apply(): Unit

    def log(port: Int, msg: String) = println(s"TestHttpServer(port=$port): $msg")

    def start(concurrency: Int): String =
        val javaBin   = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val command   = List(javaBin, "-cp", classpath, "kyo.bench.TestHttpServer", concurrency.toString)
        val builder   = new ProcessBuilder(command*)
        try
            log(port, "forking")
            val process = builder.start()
            val stopServer: StopServer = () =>
                log(port, "stopping")
                process.destroy()
            Runtime.getRuntime().addShutdownHook(new Thread:
                override def run(): Unit = stopServer()
            )
            redirect(process.getInputStream, System.out)
            redirect(process.getErrorStream, System.err)
            s"http://localhost:$port/ping"
        finally
            log(port, "forked")
        end try
    end start

    def redirect(inputStream: InputStream, outputStream: PrintStream): Unit =
        val thread = new Thread(new Runnable:
            def run(): Unit =
                val reader       = new BufferedReader(new InputStreamReader(inputStream))
                val writer       = new PrintWriter(outputStream)
                var line: String = null
                while { line = reader.readLine(); line != null } do
                    writer.println(s"[TestHttpServer] $line")
                    writer.flush()
            end run
        )
        thread.setDaemon(true)
        thread.start()
    end redirect

    class PingVerticle extends AbstractVerticle:
        override def start(): Unit =
            val serverOptions = new HttpServerOptions()
                .setMaxInitialLineLength(8192)
                .setMaxHeaderSize(8192)
                .setMaxChunkSize(8192)
                .setIdleTimeout(0)
                .setTcpKeepAlive(true)

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
            }.listen(port).andThen { result =>
                if !result.succeeded then
                    log(port, s"Failed to start server: ${result.cause}")
            }
            ()
        end start
    end PingVerticle

    def main(args: Array[String]): Unit =
        val concurrency = args(0).toInt
        log(port, "starting")
        val vertx   = Vertx.vertx()
        val options = new DeploymentOptions().setInstances(concurrency)
        vertx.deployVerticle(classOf[PingVerticle], options).andThen { result =>
            if result.succeeded then
                log(port, s"Deployed ${result.result}")
            else
                log(port, s"Deployment failed: ${result.cause}")
        }
        try
            java.util.concurrent.locks.LockSupport.park()
        finally
            log(port, "stopped")
        end try
    end main

end TestHttpServer
