package demo

import kyo.*

/** Three-tier application wired on a private user-defined network.
  *
  * ```
  * host:8080 ──► [edge: nginx] ──► [api: python http] ──► [cache: redis]
  * ```
  *
  *   - Only the edge publishes a port to the host; api and cache are not reachable from outside the network.
  *   - api and cache resolve each other by container name via the network's built-in DNS.
  *   - A curl from the host validates the full hop and prints the final response.
  *
  * Demonstrates: `Network.create`, `NetworkMode.Custom`, selective port publishing, container-name DNS resolution inside a user-defined
  * network, sizable multi-container startup with health gating.
  */
object ServiceMesh extends KyoApp:

    val netName      = "kyo-pod-demo-mesh"
    val edgeName     = "mesh-edge"
    val apiName      = "mesh-api"
    val cacheName    = "mesh-cache"
    val edgeHostPort = 18080

    private val nginxConfigScript =
        s"""|cat > /etc/nginx/conf.d/default.conf <<'EOF'
            |server {
            |    listen 80;
            |    location / {
            |        proxy_pass http://$apiName:8000;
            |        proxy_set_header Host \\$$host;
            |    }
            |}
            |EOF
            |exec nginx -g 'daemon off;'
            |""".stripMargin

    private val apiPythonScript =
        s"""|import http.server
            |import socket
            |
            |def redis_roundtrip():
            |    sock = socket.create_connection(("$cacheName", 6379), timeout=2)
            |    sock.sendall(b"*3\\r\\n$$3\\r\\nSET\\r\\n$$3\\r\\nhit\\r\\n$$2\\r\\nhi\\r\\n")
            |    sock.recv(64)  # +OK
            |    sock.sendall(b"*2\\r\\n$$3\\r\\nGET\\r\\n$$3\\r\\nhit\\r\\n")
            |    data = sock.recv(64)
            |    sock.close()
            |    # Strip RESP framing: "$$2\\r\\nhi\\r\\n" -> "hi"
            |    text = data.decode(errors="replace")
            |    if text.startswith("$$"):
            |        parts = text.split("\\r\\n", 2)
            |        if len(parts) >= 2:
            |            return parts[1]
            |    return text.strip()
            |
            |class H(http.server.BaseHTTPRequestHandler):
            |    def log_message(self, *a, **k): pass
            |    def do_GET(self):
            |        try:
            |            v = redis_roundtrip()
            |            body = f"api ok; cache returned -> {v}".encode()
            |            self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
            |            self.wfile.write(body)
            |        except Exception as e:
            |            body = f"api error: {e}".encode()
            |            self.send_response(500); self.send_header("Content-Length", str(len(body))); self.end_headers()
            |            self.wfile.write(body)
            |
            |print("api starting on 0.0.0.0:8000", flush=True)
            |http.server.ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
            |""".stripMargin

    def standUp(using Frame): Unit < (Async & Abort[ContainerException] & Scope) =
        Container.Network.init(Container.Network.Config.default.copy(name = netName).label("demo", "service-mesh")).map { _ =>
            // cache — omit command; redis:7-alpine's default CMD is redis-server
            val cacheConfig = Container.Config.default.copy(
                image = ContainerImage("redis:7-alpine"),
                name = Present(cacheName),
                networkMode = Container.Config.NetworkMode.Custom(netName, Chunk("cache")),
                // Log-based health check — redis-cli ping via exec works too, but log-grep avoids
                // an extra subprocess per probe.
                healthCheck = Container.HealthCheck.log(
                    "Ready to accept connections",
                    Schedule.fixed(200.millis).take(60)
                )
            )
            // api
            val apiConfig = Container.Config.default.copy(
                image = ContainerImage("python:3.12-alpine"),
                name = Present(apiName),
                networkMode = Container.Config.NetworkMode.Custom(netName, Chunk("api")),
                healthCheck = Container.HealthCheck.log("api starting on", Schedule.fixed(200.millis).take(30))
            ).command("python3", "-u", "-c", apiPythonScript)
            // edge — only tier with a host-published port
            val edgeConfig = Container.Config.default.copy(
                image = ContainerImage("nginx:alpine"),
                name = Present(edgeName),
                networkMode = Container.Config.NetworkMode.Custom(netName, Chunk("edge")),
                // TCP port probe via /dev/tcp (bash) or nc -z (busybox) — works on nginx:alpine.
                healthCheck = Container.HealthCheck.port(80, Schedule.fixed(200.millis).take(30))
            ).command("sh", "-c", nginxConfigScript).port(80, edgeHostPort)

            Container.initAll(Chunk(cacheConfig, apiConfig, edgeConfig)).map { containers =>
                val cache = containers(0)
                val api   = containers(1)
                val edge  = containers(2)
                Console.printLine(
                    s"[mesh] stack up — edge=${edge.id.value.take(12)} api=${api.id.value.take(12)} cache=${cache.id.value.take(12)}"
                ).andThen {
                    probeFromHost
                }
            }
        }

    def probeFromHost(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.recover[CommandException] { (e: CommandException) =>
            Console.printLine(s"[mesh] curl failed: $e").unit
        } {
            Command("curl", "-sS", s"http://localhost:$edgeHostPort/").text.map { body =>
                Console.printLine(s"[mesh] curl :$edgeHostPort -> $body").unit
            }
        }

    run {
        Console.printLine("[mesh] provisioning edge + api + cache...").andThen {
            standUp.andThen {
                Console.printLine("[mesh] done; tearing down via Scope").unit
            }
        }
    }
end ServiceMesh
