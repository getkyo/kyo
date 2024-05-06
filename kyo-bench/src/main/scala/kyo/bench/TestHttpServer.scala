package kyo.bench

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.locks.LockSupport

object TestHttpServer:

    trait StopServer:
        def apply(): Unit

    def log(port: Int, msg: String) =
        println(s"TestHttpServer(port=$port): $msg")

    def start(port: Int): String =
        val javaBin   = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val command   = List(javaBin, "-cp", classpath, "kyo.bench.TestHttpServer", port.toString)
        val builder   = new ProcessBuilder(command*)
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.redirectErrorStream(true)
        try
            log(port, "forking")
            val process = builder.start()
            val stopServer: StopServer =
                () =>
                    log(port, "stopping")
                    process.destroy()
            Runtime.getRuntime().addShutdownHook(new Thread:
                override def run(): Unit =
                    stopServer()
            )
            s"http://localhost:$port/ping"
        finally
            log(port, "forked")
        end try
    end start

    def main(args: Array[String]): Unit =
        val port = if args.isEmpty then 9999 else args(0).toInt
        log(port, "starting")
        val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)
        server.createContext(
            "/ping",
            new HttpHandler:
                val response = "pong".getBytes()
                override def handle(exchange: HttpExchange): Unit =
                    exchange.sendResponseHeaders(200, response.length)
                    val os: OutputStream = exchange.getResponseBody
                    os.write(response)
                    os.close()
                end handle
        )
        server.setExecutor(null)
        server.start()
        log(port, "started")
        try LockSupport.park()
        finally log(port, "stopped")
    end main
end TestHttpServer
