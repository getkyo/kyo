export declare class Console {
  checkErrors(): Kyo<boolean, Sync>;
  flush(): Kyo<void, Sync>;
  let_<A, S>(v: Kyo<A, S>): Kyo<A, S>;
  print(s: Text): Kyo<void, Sync>;
  printErr(s: Text): Kyo<void, Sync>;
  printLineErr(s: Text): Kyo<void, Sync>;
  println(s: Text): Kyo<void, Sync>;
  readLine(): Kyo<string, Sync & Abort<IOException>>;
  readonly unsafe: Unsafe;

  static apply(unsafe: Unsafe): Console;
  static checkErrors(): Kyo<boolean, Sync>;
  static get(): Kyo<Console, unknown>;
  readonly live: Console;
  static print<A>(v: A): Kyo<void, Sync>;
  static printErr<A>(v: A): Kyo<void, Sync>;
  static printLine<A>(v: A): Kyo<void, Sync>;
  static printLineErr<A>(v: A): Kyo<void, Sync>;
  static readLine(): Kyo<string, Sync & Abort<IOException>>;
  static use<A, S>(f: (x1: Console) => Kyo<A, S>): Kyo<A, S>;
  static withIn<A, S>(lines: Iterable<string>, v: Kyo<A, S>): Kyo<A, Sync & S>;
};