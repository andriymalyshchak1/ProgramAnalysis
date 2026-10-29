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

## The query library (`linq`)

```java
Query<String> adultNames = Query.from(people)
        .where(p -> p.age() >= 18)
        .orderBy(Person::name)
        .select(Person::name);
```

- Operators (return a new query): `where`, `select`, `selectMany`, `take`, `skip`, `distinct`,
  `orderBy`, `orderByDescending`, `thenBy`, `thenByDescending`, `groupBy`, `join`.
- Results (run the query): `toList`, `count`, `first`, `any`, `all`, `sumInt` / `sumLong` /
  `sumDouble`, `min`, `max`.

Every operator returns a new `Query` whose `iterator()` builds an iterator on top of the previous
query's iterator. So nothing runs until something iterates, and each iteration starts over from
the source. That is LINQ's deferred execution, and it is the main difference from Java Streams,
which can only be used once. `orderBy` and `groupBy` need every element, so they collect them when
iteration starts.

## Status

- Phase 0: project skeleton, placeholder grammar (`from x in xs select x`), parser test.
- Phase 1: the `linq` library, with tests for every operator and for deferred execution.
