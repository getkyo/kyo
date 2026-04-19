package kyo

/** A network address for HTTP connections — either a TCP `(host, port)` pair or a Unix domain socket path.
  *
  * Used by both client and server:
  *   - Client: identifies the connection pool key and transport target for each unique remote host.
  *   - Server: returned by `HttpServer.address` to report the address the server actually bound to.
  *
  * Unix socket addresses are selected by setting `HttpServerConfig.unixSocket` or by using `http+unix://` scheme URLs on the client.
  *
  * @see
  *   [[kyo.HttpServer]] Exposes the bound address via `server.address`
  * @see
  *   [[kyo.HttpServerConfig]] Configures the server's bind address
  * @see
  *   [[kyo.HttpUrl]] Client-side URL with Unix socket support via `http+unix://` scheme
  */
enum HttpAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end HttpAddress
