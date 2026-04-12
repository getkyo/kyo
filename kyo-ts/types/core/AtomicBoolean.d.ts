export declare class AtomicBoolean {
  compareAndSet(curr: boolean, next: boolean): Kyo<boolean, Sync>;
  get(): Kyo<boolean, Sync>;
  getAndSet(v: boolean): Kyo<boolean, Sync>;
  lazySet(v: boolean): Kyo<void, Sync>;
  set(v: boolean): Kyo<void, Sync>;
  readonly unsafe: Unsafe;
  use<A, S>(f: (x1: boolean) => Kyo<A, S>): Kyo<A, S & Sync>;

  static init(): Kyo<AtomicBoolean, Sync>;
  static initWith<A, S>(f: (x1: AtomicBoolean) => Kyo<A, S>): Kyo<A, S & Sync>;
};