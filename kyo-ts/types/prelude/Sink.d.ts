export declare class Sink {
  contramap<VV, V2, S2>(f: (x1: V2) => Kyo<VV, S2>): Sink<V2, A, S & S2>;
  contramapChunk<VV, V2, S2>(f: (x1: Chunk<V2>) => Kyo<Chunk<VV>, S2>): Sink<V2, A, S & S2>;
  contramapChunkPure<VV, V2>(f: (x1: Chunk<V2>) => Chunk<VV>): Sink<V2, A, S>;
  contramapPure<VV, V2>(f: (x1: V2) => VV): Sink<V2, A, S>;
  drain<VV, S2>(stream: Stream<VV, S2>): Kyo<A, S & S2>;
  map<B, S2>(f: (x1: A) => Kyo<B, S2>): Sink<V, B, S & S2>;
  readonly poll: Kyo<A, Poll<Chunk<V>> & S>;
  zip<VV, B, S2>(other: Sink<VV, B, S2>): Sink<VV, [A, B], S & S2>;
  zip<B, C, D, E, F, G, H, I, J>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>, f: Sink<V, F, S>, g: Sink<V, G, S>, h: Sink<V, H, S>, i: Sink<V, I, S>, j: Sink<V, J, S>): Sink<V, [A, B, C, D, E, F, G, H, I, J], S>;
  zip<B, C, D, E>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>): Sink<V, [A, B, C, D, E], S>;
  zip<B, C, D, E, F, G>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>, f: Sink<V, F, S>, g: Sink<V, G, S>): Sink<V, [A, B, C, D, E, F, G], S>;
  zip<B, C, D, E, F>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>, f: Sink<V, F, S>): Sink<V, [A, B, C, D, E, F], S>;
  zip<B, C, D, E, F, G, H>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>, f: Sink<V, F, S>, g: Sink<V, G, S>, h: Sink<V, H, S>): Sink<V, [A, B, C, D, E, F, G, H], S>;
  zip<B, C, D, E, F, G, H, I>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>, e: Sink<V, E, S>, f: Sink<V, F, S>, g: Sink<V, G, S>, h: Sink<V, H, S>, i: Sink<V, I, S>): Sink<V, [A, B, C, D, E, F, G, H, I], S>;
  zip<B, C, D>(b: Sink<V, B, S>, c: Sink<V, C, S>, d: Sink<V, D, S>): Sink<V, [A, B, C, D], S>;
  zip<B, C>(b: Sink<V, B, S>, c: Sink<V, C, S>): Sink<V, [A, B, C], S>;

  static collect<V>(): Sink<V, Chunk<V>, unknown>;
  static count<V>(): Sink<V, number, unknown>;
  static discard<V>(): Sink<V, void, unknown>;
  static empty<V>(): Sink<V, void, unknown>;
  static fold<A, V>(acc: A, f: (x1: A, x2: V) => A): Sink<V, A, unknown>;
  static foldKyo<A, V, S>(acc: A, f: (x1: A, x2: V) => Kyo<A, S>): Sink<V, A, S>;
  static foreach<V, S>(f: (x1: V) => Kyo<void, S>): Sink<V, void, S>;
  static foreachChunk<V, S>(f: (x1: Chunk<V>) => Kyo<void, S>): Sink<V, void, S>;
};