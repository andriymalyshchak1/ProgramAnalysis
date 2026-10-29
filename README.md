# linq-java

LINQ-style queries for Java (ECE 361G project).

Planned pieces:

1. `linq` — a lazy `Query<T>` library (where, select, orderBy, ...).
2. `linqc` — a translator that turns query syntax
   (`from p in people where p.age >= 18 select p.name`) into calls to that library.
3. Extra credit: an analysis that merges a query that only filters another query's result.

## Build and test

```
./gradlew build
```

Grammars are in `src/main/antlr/`. The build generates the lexer, parser and visitor into
`build/generated-src/antlr/main/`.

## Status

Phase 0: project skeleton, placeholder grammar (`from x in xs select x`), one parser test.
