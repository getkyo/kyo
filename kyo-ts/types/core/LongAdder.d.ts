export declare class LongAdder {
  add(v: number): Kyo<void, Sync>;
  decrement(): Kyo<void, Sync>;
  get(): Kyo<number, Sync>;
  increment(): Kyo<void, Sync>;
  reset(): Kyo<void, Sync>;
  sumThenReset(): Kyo<number, Sync>;
  readonly unsafe: Unsafe;

  static init(): Kyo<LongAdder, Sync>;
  static initWith<A, S>(f: (x1: LongAdder) => Kyo<A, S>): Kyo<A, Sync & S>;
};