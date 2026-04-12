export declare class Signal {
  asRef(): Kyo<SignalRef<A>, Async & Scope>;
  current(): Kyo<A, Sync>;
  currentWith<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, S & Sync>;
  flatMap<B>(f: (x1: A) => Signal<B>): Signal<B>;
  map<B>(f: (x1: A) => B): Signal<B>;
  next(): Kyo<A, Async>;
  nextWith<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, S & Async>;
  streamChanges(): Stream<A, Async>;
  streamCurrent(): Stream<A, Async>;
  zip<B>(other: Signal<B>): Signal<[A, B]>;
  zipLatest<B>(other: Signal<B>): Signal<[A, B]>;

  static awaitAny(signals: Signal<unknown>[]): Kyo<void, Async>;
  static collectAll<A>(signals: Signal<A>[]): Signal<Chunk<A>>;
  static collectAllLatest<A>(signals: Signal<A>[]): Signal<Chunk<A>>;
  static initConst<A>(value: A): Signal<A>;
  static initConstWith<A, B, S>(value: A, f: (x1: Signal<A>) => Kyo<B, S>): Kyo<B, S>;
  static initRef<A>(initial: A): Kyo<SignalRef<A>, Sync>;
  static initRefWith<A, B, S>(initial: A, f: (x1: SignalRef<A>) => Kyo<B, S>): Kyo<B, S & Sync>;
};