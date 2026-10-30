# linq-java

LINQ-style queries for Java (ECE 361G project).

1. `linq`: a lazy `Query<T>` library (where, select, orderBy, ...).
2. `linqc`: a translator that turns query syntax inside Java, such as
   `from p in people where p.age() >= 18 select p.name()`, into calls to that library.
3. Extra credit: an analysis that finds a query that only filters another query's result,
   and merges the filter into the first query (`linqc --report`, `linqc --merge`).

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
  Every change is marked "added for linq-java". One upstream bug is fixed and marked "changed for
  linq-java": `o instanceof Integer k` used to parse as a two-word type instead of a pattern. The query keywords (`from`, `where`, `select`, ...)
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

## Merging queries (extra credit)

```
./gradlew -q run --args="--report examples/Merging.jlinq"        # what can be merged, and why not
./gradlew -q run --args="--merge --run examples/Merging.jlinq"   # merge, then translate and run
```

When a query only filters another query's result, its filter moves into the first query:

```java
var adults = from p in people where p.age() >= 18 select p;
var locals = from a in adults where a.city().equals("Austin") select a;
// becomes
var adults = from p in people where p.age() >= 18 && p.city().equals("Austin") select p;
var locals = linq.Query.from(adults) /* filter merged into the query on line 1 */;
```

The filter goes before any `orderby`. If the first query's `select` changes the elements, the first
query continues with `into a where ... select a` instead, and the same happens if renaming `a` to
`p` would clash with another name.

`QueryMerger` merges a query that reads variable `v` only if all of these hold:

1. It only filters: `from a in v`, then `where` clauses, then `select a`.
2. It isn't inside a lambda or another query, and its method has no statement the control-flow
   graph doesn't model (`try`, `switch`, labeled `break`/`continue`).
3. **Reaching definitions:** the only assignment to `v` that can reach it is `v = <first query>`,
   meaning the query itself and not, say, its `toList()`.
4. That assignment reaches no other use of `v`, so nothing else sees the extra filter.
5. Every name the filter uses means the same variable at the first query. For example, a filter
   that uses a loop variable can't move out of the loop.

Why the merge is safe: queries are deferred, so both filters run only when the result is iterated,
before and after the merge. The first query stays where it is, so it still reads its source at the
same point. That's why reassigning `people` between the two queries doesn't block a merge.

The parts:

- `linqc.analysis.ControlFlowGraph`: a node per simple statement or condition. Each node
  records the locals it assigns and reads, and the locals in scope there.
  - Lambdas and query clauses run later and can't assign the method's locals, so only their
    reads count.
  - An assignment that might not run, such as `c ? (a = 1) : 2` or one after `&&`, doesn't
    replace earlier ones.
- `linqc.analysis.ReachingDefinitions`: the textbook gen/kill analysis, iterated until nothing
  changes.
- `linqc.QueryMerger`: the checks above, and the rewrite. In a chain `a → b → c` it merges `c` into
  `b` first, then `b` into `a`.

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
- `src/test/java/linqc/analysis`: control-flow graphs and reaching definitions on small methods.
- `src/test/java/linqc/QueryMergerTest`: merges, and one refused case per condition.
- `src/test/java/linqc/ExamplesTest`: every `examples/Name.jlinq` is translated, compiled and
  run, and must print exactly `examples/Name.out`. It runs again after merging queries, and the
  output must be the same.

## Status

- Phase 0: project skeleton.
- Phase 1: the `linq` library.
- Phase 2: query syntax: grammar, translator, `linqc` command, examples.
- Phase 3: query-merge analysis (control-flow graph, reaching definitions, `--report`, `--merge`).
