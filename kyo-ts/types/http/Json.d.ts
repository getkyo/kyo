export declare class Json {
  decode(json: string): Result<string, A>;
  encode(value: A): string;

  static apply<A>(): Json<A>;
  static derived<A>(): Json<A>;
  static fromZio<A>(zs: Schema<A>): Json<A>;
  readonly given_Json_Boolean: Json<boolean>;
  readonly given_Json_Byte: Json<number>;
  readonly given_Json_Char: Json<Char>;
  readonly given_Json_Double: Json<number>;
  static given_Json_Either<A, B>(): Json<Either<A, B>>;
  readonly given_Json_Float: Json<number>;
  readonly given_Json_Int: Json<number>;
  static given_Json_List<A>(): Json<A[]>;
  readonly given_Json_Long: Json<number>;
  static given_Json_Map<A, B>(): Json<Map<A, B>>;
  static given_Json_Maybe<A>(): Json<Maybe<A>>;
  static given_Json_Option<A>(): Json<A | undefined>;
  static given_Json_Seq<A>(): Json<A[]>;
  static given_Json_Set<A>(): Json<Set<A>>;
  readonly given_Json_Short: Json<number>;
  readonly given_Json_String: Json<string>;
  readonly given_Json_Unit: Json<void>;
  static given_Json_Vector<A>(): Json<A[]>;
};