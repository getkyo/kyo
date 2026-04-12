export declare class Record {
  and<A>(other: Record<A>): Record<F & A>;
  compact(): Record<F>;
  fields(): string[];
  getField<Name, V>(name: Name): Value;
  selectDynamic<Name>(name: Name): Value;
  readonly size: number;
  readonly toDict: Dict<string, unknown>;
  widen<A, B>(): Record<B>;
  zip<F2>(other: Record<F2>): Record<Zipped<AsTuple>>;

  readonly empty: Record<unknown>;
  readonly field: Field;
  static given_CanEqual_Record_Record<F>(): CanEqual<Record<F>, Record<F>>;
  static render<F>(): Render<Record<F>>;
  static stage<A>(): StageOps<A, AsTuple>;
};