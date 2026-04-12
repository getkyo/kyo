export declare class HttpSseEvent {
  readonly data: A;
  readonly event: Maybe<string>;
  readonly id: Maybe<string>;
  readonly retry: Maybe<Duration>;

  static apply<A>(data: A, event: Maybe<string>, id: Maybe<string>, retry: Maybe<Duration>): HttpSseEvent<A>;
};