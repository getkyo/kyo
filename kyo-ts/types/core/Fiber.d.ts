export declare class Fiber {
  block<E>(timeout: Duration): Kyo<Result<E | Timeout, A>, Sync & S>;
  done(): Kyo<boolean, Sync>;
  flatMap<B, S2>(f: (x1: Kyo<A, S>) => Kyo<Fiber<B, S2>, Sync>): Kyo<Fiber<B, S & S2>, Sync>;
  get<E>(): Kyo<A, Abort<E> & Async & S>;
  getResult<E>(): Kyo<Result<E, A>, Async & S>;
  interrupt<E>(): Kyo<boolean, Sync>;
  interrupt<E>(error: Error<E>): Kyo<boolean, Sync>;
  interruptDiscard<E>(error: Error<E>): Kyo<void, Sync>;
  map<B>(f: (x1: A) => Kyo<B, Sync>): Kyo<Fiber<B, S>, Sync>;
  mapResult<E, E2, B, S2>(f: (x1: Result<E, Kyo<A, S>>) => Kyo<Result<E2, Kyo<B, S2>>, Sync>): Kyo<Fiber<B, Abort<E2> & S & S2>, Sync>;
  mask(): Kyo<Fiber<A, S>, Sync>;
  onComplete<E>(f: (x1: Result<E, Kyo<A, S>>) => Kyo<unknown, Sync>): Kyo<void, Sync>;
  onInterrupt<E>(f: (x1: Error<E>) => Kyo<unknown, Sync>): Kyo<void, Sync>;
  poll<E>(): Kyo<Maybe<Result<E, Kyo<A, S>>>, Sync>;
  unsafe(): Unsafe<A, S>;
  use<E, B, S2>(f: (x1: A) => Kyo<B, S2>): Kyo<B, Abort<E> & Async & S & S2>;
  useResult<E, B, S2>(f: (x1: Result<E, Kyo<A, S>>) => Kyo<B, S2>): Kyo<B, Async & S2>;
  waiters(): Kyo<number, Sync>;

  static fail<E>(ex: E): Fiber<never, Abort<E>>;
  static never(): Kyo<Fiber<never, unknown>, Sync>;
  static panic(ex: Throwable): Fiber<never, unknown>;
  static succeed<A>(v: A): Fiber<A, unknown>;
  readonly unit: Fiber<void, unknown>;
};