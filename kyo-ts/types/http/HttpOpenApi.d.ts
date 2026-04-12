export declare class HttpOpenApi {
  readonly components: Components | undefined;
  readonly info: Info;
  readonly openapi: string;
  readonly paths: Map<string, PathItem>;
  toFile(path: string): void;
  toJson(): string;
};