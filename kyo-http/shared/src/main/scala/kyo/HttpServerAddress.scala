package kyo

enum HttpServerAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end HttpServerAddress
