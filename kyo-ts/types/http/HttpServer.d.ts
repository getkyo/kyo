export declare class HttpServer {
  await(): Kyo<void, Async>;
  close(gracePeriod: Duration): Kyo<void, Async>;
  close(): Kyo<void, Async>;
  closeNow(): Kyo<void, Async>;
  readonly host: string;
  readonly port: number;

  static init(handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async & Scope>;
  static init(backend: Server, port: number, host: string, handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async & Scope>;
  static init(backend: Server, config: HttpServerConfig, handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async & Scope>;
  static initUnscoped(handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async>;
  static initUnscoped(backend: Server, config: HttpServerConfig, handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async>;
  static initUnscoped(backend: Server, port: number, host: string, handlers: HttpHandler<unknown, unknown, unknown>[]): Kyo<HttpServer, Async>;
  static initUnscopedWith<A, S>(handlers: HttpHandler<unknown, unknown, unknown>[], f: (x1: HttpServer) => Kyo<A, S>): Kyo<A, S & Async & Scope>;
  static initUnscopedWith<A, S>(backend: Server, port: number, host: string, handlers: HttpHandler<unknown, unknown, unknown>[], f: (x1: HttpServer) => Kyo<A, S>): Kyo<A, S & Async & Scope>;
  static initWith<A, S>(backend: Server, port: number, host: string, handlers: HttpHandler<unknown, unknown, unknown>[], f: (x1: HttpServer) => Kyo<A, S>): Kyo<A, S & Async & Scope>;
  static initWith<A, S>(handlers: HttpHandler<unknown, unknown, unknown>[], f: (x1: HttpServer) => Kyo<A, S>): Kyo<A, S & Async & Scope>;
};