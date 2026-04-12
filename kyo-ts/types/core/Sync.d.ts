export declare class Sync {

  static defer<A, S>(f: (x1: Safepoint) => Kyo<A, S>): Kyo<A, Sync & S>;
  static ensure<A, S>(f: (x1: Maybe<Error<unknown>>) => Kyo<unknown, Sync & Abort<Throwable>>, v: () => Kyo<A, S>): Kyo<A, Sync & S>;
};