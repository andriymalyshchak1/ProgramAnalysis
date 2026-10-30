# linq-java

LINQ-style queries for Java (ECE 361G project).

1. `linq`: a lazy `Query<T>` library (where, select, orderBy, ...).
2. `linqc`: a translator that turns query syntax inside Java, such as
   `from p in people where p.age() >= 18 select p.name()`, into calls to that library.
3. Extra credit (not started): an analysis that merges a query that only filters another
   query's result.

## Build and test

```
./gradlew build
```

The grammars are in `src/main/antlr/`. The build generates the lexer, parser and visitor into
`build/generated-src/antlr/main/`.

## Query syntax (`.jlinq` files)

A `.jlinq` file is Java in which a query expression can appear anywhere an expression can:

```java
var adults = from p in people
             where p.age() >= 18
             orderby p.name()
             select p.name();
```

```
./gradlew -q run --args="examples/Adults.jlinq"        # print the translated Java
./gradlew -q run --args="--run examples/Adults.jlinq"  # translate, compile and run it
./gradlew -q run --args="-d out examples/*.jlinq"      # write out/Name.java for each file
```

The translator follows the C# specification's rules. Each clause becomes one method call:

| Query syntax | Generated Java |
|---|---|
| `from p in people` | `linq.Query.from(people)` |
| `where p.age() >= 18` | `.where(p -> p.age() >= 18)` |
| `orderby p.age() descending, p.name()` | `.orderByDescending(p -> p.age()).thenBy(p -> p.name())` |
| `select p.name()` | `.select(p -> p.name())` |
| `select p` (after other clauses) | nothing: it would return each element unchanged |
| `group p by p.city()` | `.groupBy(p -> p.city())` |
| `group p.name() by p.city()` | `.groupBy(p -> p.city(), p -> p.name())` |
| `... into g ...` | the chain continues, with `g` as the new variable |

How it works:

- **Grammar.** `JLinqLexer.g4` and `JLinqParser.g4` are the Java grammar from
  [grammars-v4](https://github.com/antlr/grammars-v4/tree/master/java/java) plus the query rules.
  Every change is marked "added for linq-java". The query keywords (`from`, `where`, `select`, ...)
  are contextual, as in C#. Outside a query they are ordinary names, so `System.in`,
  `a.equals(b)` and a variable called `select` all still work.
- **Translation.** The translator is purely syntactic and never needs to know types; javac
  type-checks the generated code. ANTLR's `TokenStreamRewriter` replaces each query and copies
  everything else exactly, comments and spacing included.
- **Line numbers.** Each clause of a multi-line query stays on its original line, so javac's
  errors for the generated code point at the right line of the `.jlinq` file.

Not supported yet:

- `let`, `join` and a second `from` clause. They parse, and the translator reports them with
  their position. Each needs two variables per element, which Java can't express with C#'s
  anonymous types.
- Arrays of primitives such as `int[]`.
- As in C#, a query variable can't have the same name as a local variable already in scope.

## The query library (`linq`)

```java
Query<String> adultNames = Query.from(people)
        .where(p -> p.age() >= 18)
        .orderBy(Person::name)
        .select(Person::name);
```

- Sources: `Query.from(iterable)`, `Query.from(array)`, `Query.of(a, b, c)`.
- Operators (return a new query): `where`, `select`, `selectMany`, `take`, `skip`, `distinct`,
  `orderBy`, `orderByDescending`, `thenBy`, `thenByDescending`, `groupBy`, `join`.
- Results (run the query): `toList`, `count`, `first`, `any`, `all`, `sumInt` / `sumLong` /
  `sumDouble`, `min`, `max`.

Every operator returns a new `Query` whose `iterator()` builds an iterator on top of the previous
query's iterator. So nothing runs until something iterates, and each iteration starts over from
the source. That is LINQ's deferred execution, and it is the main difference from Java Streams,
which can only be used once. `orderBy` and `groupBy` need every element, so they collect them when
iteration starts.

## Tests

- `src/test/java/linq`: the library, including deferred execution.
- `src/test/java/linqc/parser`: the grammar, including query keywords used as ordinary names.
- `src/test/java/linqc/TranslatorTest`: query → Java translations, exact copying of other code,
  line numbers and error messages.
- `src/test/java/linqc/ExamplesTest`: every `examples/Name.jlinq` is translated, compiled and
  run, and must print exactly `examples/Name.out`.

## Status

- Phase 0: project skeleton.
- Phase 1: the `linq` library.
- Phase 2: query syntax: grammar, translator, `linqc` command, examples.
