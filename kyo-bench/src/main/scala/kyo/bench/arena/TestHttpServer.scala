package kyo.bench.arena

import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.http.Http2Settings
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

object TestHttpServer:
    val port         = 8888
    val maxRetries   = 20
    val retryDelayMs = 100

    def log(port: Int, msg: String) = println(s"TestHttpServer(port=$port): $msg")

    def waitForServer(url: String): Boolean =
        def tryConnect(): Try[Boolean] = Try {
            val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
            connection.setRequestMethod("GET")
            connection.setConnectTimeout(1000)
            connection.setReadTimeout(1000)
            try
                val responseCode = connection.getResponseCode()
                responseCode == 200
            finally
                connection.disconnect()
            end try
        }

        var retries = 0
        while retries < maxRetries do
            tryConnect() match
                case Success(true) =>
                    log(port, "Server is ready")
                    return true
                case _ =>
                    retries += 1
                    if retries < maxRetries then
                        Thread.sleep(retryDelayMs)
                    log(port, s"Waiting for server... (attempt $retries/$maxRetries)")
        end while

        log(port, "Server failed to start after maximum retries")
        false
    end waitForServer

    def start(concurrency: Int): String =
        val javaBin   = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val command   = List(javaBin, "-cp", classpath, "kyo.bench.TestHttpServer", concurrency.toString)
        val builder   = new ProcessBuilder(command*)
        try
            log(port, "forking")
            val process = builder.start()
            Runtime.getRuntime().addShutdownHook(new Thread:
                override def run(): Unit =
                    log(port, "stopping")
                    process.destroy())
            redirect(process.getInputStream, System.out)
            redirect(process.getErrorStream, System.err)

            val serverUrl = s"http://localhost:$port/ping"
            if !waitForServer(serverUrl) then
                throw new RuntimeException("Server failed to start")

            serverUrl
        finally
            log(port, "forked")
        end try
    end start

    def redirect(inputStream: InputStream, outputStream: PrintStream): Unit =
        val runnable =
            new Runnable:
                def run(): Unit =
                    val reader       = new BufferedReader(new InputStreamReader(inputStream))
                    val writer       = new PrintWriter(outputStream)
                    var line: String = null
                    while { line = reader.readLine(); line != null } do
                        writer.println(s"[TestHttpServer] $line")
                        writer.flush()
                end run
        val thread = new Thread(runnable)
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
