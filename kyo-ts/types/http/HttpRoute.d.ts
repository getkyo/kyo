export declare class HttpRoute {
  error<E2>(s: HttpStatus): HttpRoute<In, Out, E | E2>;
  filter<ReqUse, ReqAdd, ResUse, ResAdd, E2>(f: HttpFilter<ReqUse, ReqAdd, ResUse, ResAdd, E2>): HttpRoute<In & ReqAdd, Out & ResAdd, E | E2>;
  readonly filter: HttpFilter<unknown, unknown, unknown, unknown, unknown>;
  handler<E2>(f: (x1: HttpRequest<In>) => Kyo<HttpResponse<Out>, Async & Abort<E2 | Halt>>): HttpHandler<In, Out, E2>;
  readonly metadata: Metadata;
  metadata(f: (x1: Metadata) => Metadata): HttpRoute<In, Out, E>;
  readonly method: HttpMethod;
  pathAppend<In2>(suffix: HttpPath<In2>): HttpRoute<In & In2, Out, E>;
  pathPrepend<In2>(prefix: HttpPath<In2>): HttpRoute<In & In2, Out, E>;
  readonly request: RequestDef<In>;
  readonly response: ResponseDef<Out>;
};