export declare class TChunk {
  append(value: A): Kyo<void, STM>;
  compact(): Kyo<void, STM>;
  concat(other: Chunk<A>): Kyo<void, STM>;
  drop(n: number): Kyo<void, STM>;
  dropRight(n: number): Kyo<void, STM>;
  filter<S>(p: (x1: A) => Kyo<boolean, S>): Kyo<void, STM & S>;
  get(index: number): Kyo<A, STM>;
  head(): Kyo<A, STM>;
  isEmpty(): Kyo<boolean, STM>;
  last(): Kyo<A, STM>;
  size(): Kyo<number, STM>;
  slice(from: number, until: number): Kyo<void, STM>;
  snapshot(): Kyo<Chunk<A>, STM>;
  take(n: number): Kyo<void, STM>;
  use<B, S>(f: (x1: Chunk<A>) => Kyo<B, S>): Kyo<B, STM & S>;

  static init<A>(values: A[]): Kyo<TChunk<A>, Sync>;
  static init<A>(): Kyo<TChunk<A>, Sync>;
};