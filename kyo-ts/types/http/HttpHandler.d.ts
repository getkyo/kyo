export declare class HttpHandler {
  apply(request: HttpRequest<In>): Kyo<HttpResponse<Out>, Async & Abort<E | Halt>>;
  readonly route: HttpRoute<In, Out, E>;

  static health(): HttpHandler<unknown, Field<"body", string>, never>;
};