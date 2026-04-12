export declare class AtomicLong {
  addAndGet(v: number): Kyo<number, Sync>;
  compareAndSet(curr: number, next: number): Kyo<boolean, Sync>;
  decrementAndGet(): Kyo<number, Sync>;
  get(): Kyo<number, Sync>;
  getAndAdd(v: number): Kyo<number, Sync>;
  getAndDecrement(): Kyo<number, Sync>;
  getAndIncrement(): Kyo<number, Sync>;
  getAndSet(v: number): Kyo<number, Sync>;
  getAndUpdate(f: (x1: number) => number): Kyo<number, Sync>;
  incrementAndGet(): Kyo<number, Sync>;
  lazySet(v: number): Kyo<void, Sync>;
  set(v: number): Kyo<void, Sync>;
  readonly unsafe: Unsafe;
  updateAndGet(f: (x1: number) => number): Kyo<number, Sync>;
  use<A, S>(f: (x1: number) => Kyo<A, S>): Kyo<A, S & Sync>;

  static init(): Kyo<AtomicLong, Sync>;
  static initWith<A, S>(f: (x1: AtomicLong) => Kyo<A, S>): Kyo<A, S & Sync>;
};