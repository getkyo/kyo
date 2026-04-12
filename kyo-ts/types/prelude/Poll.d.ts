export declare class Poll {

  static andMap<V, A, S>(f: (x1: Maybe<V>) => Kyo<A, S>): Kyo<A, Poll<V> & S>;
  readonly eliminatePoll: eliminatePoll;
  static fold<V, A, S>(acc: A, f: (x1: A, x2: V) => Kyo<A, S>): Kyo<A, Poll<V> & S>;
  static one<V>(): Kyo<Maybe<V>, Poll<V>>;
};