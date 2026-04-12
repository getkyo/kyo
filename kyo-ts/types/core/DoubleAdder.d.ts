export declare class DoubleAdder {
  add(v: number): Kyo<void, Sync>;
  get(): Kyo<number, Sync>;
  reset(): Kyo<void, Sync>;
  sumThenReset(): Kyo<number, Sync>;
  readonly unsafe: Unsafe;

  static init(): Kyo<DoubleAdder, Sync>;
  static initWith<A, S>(f: (x1: DoubleAdder) => Kyo<A, S>): Kyo<A, Sync & S>;
};