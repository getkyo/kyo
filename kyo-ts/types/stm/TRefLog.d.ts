export declare class TRefLog {
  get<A>(ref: TRef<A>): Maybe<Entry<A>>;
  put<A>(ref: TRef<A>, entry: Entry<A>): TRefLog;
  toMap(): Map<TRef<unknown>, Entry<unknown>>;

  readonly empty: TRefLog;
  readonly isolate: Isolate<Var<TRefLog>, unknown, Var<TRefLog>>;
};