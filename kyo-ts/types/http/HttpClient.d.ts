export declare class HttpClient {
  close(): Kyo<void, Async>;
  close(gracePeriod: Duration): Kyo<void, Async>;
  closeNow(): Kyo<void, Async>;
  let_<A, S>(v: Kyo<A, S>): Kyo<A, S>;
  sendWith<In, Out, A>(route: HttpRoute<In, Out, unknown>, request: HttpRequest<In>, f: (x1: HttpResponse<Out>) => Kyo<A, Async & Abort<HttpException>>): Kyo<A, Async & Abort<HttpException>>;

  static init(backend: Client, maxConnectionsPerHost: number, idleConnectionTimeout: Duration): Kyo<HttpClient, Async & Scope>;
  static initUnscoped(backend: Client, maxConnectionsPerHost: number, idleConnectionTimeout: Duration): Kyo<HttpClient, Sync>;
  static update<A, S>(f: (x1: HttpClient) => HttpClient, v: Kyo<A, S>): Kyo<A, S>;
  static use<A, S>(f: (x1: HttpClient) => Kyo<A, S>): Kyo<A, S>;
  static withConfig<A, S>(f: (x1: HttpClientConfig) => HttpClientConfig, v: Kyo<A, S>): Kyo<A, S>;
};