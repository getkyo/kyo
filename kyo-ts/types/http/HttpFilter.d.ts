export declare class HttpFilter {
  adapt<In, Out>(): HttpFilter<In & ReqAdd, unknown, Out & ResAdd, unknown, E>;
  andThen<RI2, RO2, SI2, SO2, E2>(that: HttpFilter<RI2, RO2, SI2, SO2, E2>): HttpFilter<ReqUse & RI2, ReqAdd & RO2, ResUse & SI2, ResAdd & SO2, E | E2>;
  apply<In, Out, E2>(request: HttpRequest<In & ReqUse>, next: (x1: HttpRequest<In & ReqUse & ReqAdd>) => Kyo<HttpResponse<Out & ResUse>, Async & Abort<E2 | Halt>>): Kyo<HttpResponse<Out & ResUse & ResAdd>, Async & Abort<E | E2 | Halt>>;

  readonly client: client;
  readonly noop: Passthrough<never>;
  readonly server: server;
};