export declare class Emit {

  readonly eliminateEmit: eliminateEmit;
  readonly isolate: isolate;
  static runFold<V, A, S, VR, B, S2>(acc: A, f: (x1: A, x2: V) => Kyo<A, S>, v: Kyo<B, Emit<V> & Emit<VR> & S2>): Kyo<[A, B], SReduced & S & S2>;
  static value<V>(value: V): Kyo<void, Emit<V>>;
  static valueWith<V, A, S>(value: V, f: () => Kyo<A, S>): Kyo<A, S & Emit<V>>;
};