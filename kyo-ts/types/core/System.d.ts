export declare class System {
  env<E, A>(name: string): Kyo<Maybe<A>, Abort<E> & Sync>;
  let_<A, S>(f: () => Kyo<A, S>): Kyo<A, S>;
  lineSeparator(): Kyo<string, Sync>;
  operatingSystem(): Kyo<OS, Sync>;
  property<E, A>(name: string): Kyo<Maybe<A>, Abort<E> & Sync>;
  readonly unsafe: Unsafe;
  userName(): Kyo<string, Sync>;

  static apply(u: Unsafe): System;
  static lineSeparator(): Kyo<string, Sync>;
  readonly live: System;
  static operatingSystem(): Kyo<OS, Sync>;
  static userName(): Kyo<string, Sync>;
};