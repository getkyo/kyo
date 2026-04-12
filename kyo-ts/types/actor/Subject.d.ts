export declare class Subject {
  ask<B>(f: (x1: Subject<B>) => A): Kyo<B, Async & Abort<Closed>>;
  send(message: A): Kyo<void, Async & Abort<Closed>>;
  trySend(message: A): Kyo<boolean, Sync & Abort<Closed>>;

  static init<A>(queue: Unbounded<A>): Subject<A>;
  static init<A>(send: (x1: never) => (x1: A) => Kyo<void, Async & Abort<Closed>>, trySend: (x1: never) => (x1: A) => Kyo<boolean, Sync & Abort<Closed>>): Subject<A>;
  static noop<A>(): Subject<A>;
};