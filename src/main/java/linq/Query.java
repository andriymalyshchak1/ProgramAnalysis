package linq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * A LINQ-style query over any {@link Iterable}.
 *
 * <p>Like LINQ, queries use deferred execution. Building a query (where, select, orderBy, ...)
 * runs nothing. Each call to {@link #iterator()} runs the whole pipeline again from the source,
 * so a query can be iterated any number of times and sees changes made to the source in between.
 * Methods that return a single value instead of a query (count, first, toList, ...) run right away.
 *
 * <p>Arguments are still checked as soon as an operator is called, as in LINQ.
 */
public class Query<T> implements Iterable<T> {

    private final Iterable<T> source;

    Query(Iterable<T> source) {
        this.source = source;
    }

    public static <T> Query<T> from(Iterable<T> source) {
        return new Query<>(Objects.requireNonNull(source, "source"));
    }

    @SafeVarargs
    public static <T> Query<T> of(T... items) {
        return from(Arrays.asList(items));
    }

    @Override
    public Iterator<T> iterator() {
        return source.iterator();
    }

    // ---- Filtering, projection, partitioning ----------------------------------------------

    public Query<T> where(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return new Query<>(() -> {
            Iterator<T> it = iterator();
            return new LazyIterator<T>() {
                @Override
                protected T computeNext() {
                    while (it.hasNext()) {
                        T item = it.next();
                        if (predicate.test(item)) {
                            return item;
                        }
                    }
                    return endOfData();
                }
            };
        });
    }

    public <R> Query<R> select(Function<? super T, ? extends R> selector) {
        Objects.requireNonNull(selector, "selector");
        return new Query<>(() -> {
            Iterator<T> it = iterator();
            return new LazyIterator<R>() {
                @Override
                protected R computeNext() {
                    return it.hasNext() ? selector.apply(it.next()) : endOfData();
                }
            };
        });
    }

    /** Maps each element to a sequence and flattens the results into one query. */
    public <R> Query<R> selectMany(Function<? super T, ? extends Iterable<? extends R>> selector) {
        Objects.requireNonNull(selector, "selector");
        return new Query<>(() -> {
            Iterator<T> outer = iterator();
            return new LazyIterator<R>() {
                private Iterator<? extends R> inner = Collections.emptyIterator();

                @Override
                protected R computeNext() {
                    while (!inner.hasNext()) {
                        if (!outer.hasNext()) {
                            return endOfData();
                        }
                        inner = selector.apply(outer.next()).iterator();
                    }
                    return inner.next();
                }
            };
        });
    }

    /** The first {@code count} elements. Never asks the source for more than that. */
    public Query<T> take(int count) {
        return new Query<>(() -> {
            Iterator<T> it = iterator();
            return new LazyIterator<T>() {
                private int remaining = count;

                @Override
                protected T computeNext() {
                    if (remaining <= 0 || !it.hasNext()) {
                        return endOfData();
                    }
                    remaining--;
                    return it.next();
                }
            };
        });
    }

    /** Everything after the first {@code count} elements. */
    public Query<T> skip(int count) {
        return new Query<>(() -> {
            Iterator<T> it = iterator();
            return new LazyIterator<T>() {
                private int toSkip = count;

                @Override
                protected T computeNext() {
                    while (toSkip > 0 && it.hasNext()) {
                        it.next();
                        toSkip--;
                    }
                    return it.hasNext() ? it.next() : endOfData();
                }
            };
        });
    }

    /** Drops repeated elements (by {@code equals}), keeping the first occurrence of each. */
    public Query<T> distinct() {
        return new Query<>(() -> {
            Iterator<T> it = iterator();
            Set<T> seen = new HashSet<>();
            return new LazyIterator<T>() {
                @Override
                protected T computeNext() {
                    while (it.hasNext()) {
                        T item = it.next();
                        if (seen.add(item)) {
                            return item;
                        }
                    }
                    return endOfData();
                }
            };
        });
    }

    // ---- Ordering -------------------------------------------------------------------------

    /** Sorts by a key, smallest first. The sort is stable, and null keys come first, as in LINQ. */
    public <K extends Comparable<? super K>> OrderedQuery<T> orderBy(Function<? super T, ? extends K> key) {
        return new OrderedQuery<>(this, OrderedQuery.<T, K>ascending(key));
    }

    public <K extends Comparable<? super K>> OrderedQuery<T> orderByDescending(Function<? super T, ? extends K> key) {
        return new OrderedQuery<>(this, OrderedQuery.<T, K>ascending(key).reversed());
    }

    // ---- Grouping and joining -------------------------------------------------------------

    /** Groups elements by key. Groups appear in the order their keys first appear in the source. */
    public <K> Query<Grouping<K, T>> groupBy(Function<? super T, ? extends K> key) {
        return groupBy(key, item -> item);
    }

    /** Groups elements by key, keeping {@code element(item)} in each group instead of the item. */
    public <K, E> Query<Grouping<K, E>> groupBy(Function<? super T, ? extends K> key,
                                                Function<? super T, ? extends E> element) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(element, "element");
        return new Query<>(() -> {
            // Grouping needs every element, so it happens when iteration starts.
            Map<K, List<E>> groups = new LinkedHashMap<>();
            for (T item : this) {
                groups.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(element.apply(item));
            }
            List<Grouping<K, E>> result = new ArrayList<>();
            groups.forEach((k, items) -> result.add(new Grouping<>(k, items)));
            return result.iterator();
        });
    }

    /**
     * Inner join: pairs each element with every element of {@code inner} that has an equal key.
     * Results follow this query's order, then {@code inner}'s order. Null keys never match.
     */
    public <U, K, R> Query<R> join(Iterable<U> inner,
                                   Function<? super T, ? extends K> outerKey,
                                   Function<? super U, ? extends K> innerKey,
                                   BiFunction<? super T, ? super U, ? extends R> result) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(outerKey, "outerKey");
        Objects.requireNonNull(innerKey, "innerKey");
        Objects.requireNonNull(result, "result");
        return new Query<>(() -> {
            Iterator<T> outer = iterator();
            return new LazyIterator<R>() {
                private Map<K, List<U>> lookup;
                private T current;
                private Iterator<U> matches = Collections.emptyIterator();

                @Override
                protected R computeNext() {
                    if (lookup == null) {
                        lookup = lookup(inner, innerKey);
                    }
                    while (!matches.hasNext()) {
                        if (!outer.hasNext()) {
                            return endOfData();
                        }
                        current = outer.next();
                        K k = outerKey.apply(current);
                        matches = k == null ? Collections.emptyIterator()
                                            : lookup.getOrDefault(k, List.of()).iterator();
                    }
                    return result.apply(current, matches.next());
                }
            };
        });
    }

    private static <U, K> Map<K, List<U>> lookup(Iterable<U> items, Function<? super U, ? extends K> key) {
        Map<K, List<U>> lookup = new HashMap<>();
        for (U item : items) {
            K k = key.apply(item);
            if (k != null) {
                lookup.computeIfAbsent(k, unused -> new ArrayList<>()).add(item);
            }
        }
        return lookup;
    }

    // ---- Results (these run the query) ----------------------------------------------------

    /** A new, modifiable list holding the query's current results. */
    public List<T> toList() {
        List<T> list = new ArrayList<>();
        for (T item : this) {
            list.add(item);
        }
        return list;
    }

    public int count() {
        int count = 0;
        for (T ignored : this) {
            count++;
        }
        return count;
    }

    /** @throws NoSuchElementException if the query is empty */
    public T first() {
        Iterator<T> it = iterator();
        if (!it.hasNext()) {
            throw new NoSuchElementException("query has no elements");
        }
        return it.next();
    }

    /** @throws NoSuchElementException if no element matches */
    public T first(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (T item : this) {
            if (predicate.test(item)) {
                return item;
            }
        }
        throw new NoSuchElementException("no element matches the predicate");
    }

    public boolean any() {
        return iterator().hasNext();
    }

    public boolean any(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (T item : this) {
            if (predicate.test(item)) {
                return true;
            }
        }
        return false;
    }

    /** True if every element matches. True for an empty query, as in LINQ. */
    public boolean all(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        for (T item : this) {
            if (!predicate.test(item)) {
                return false;
            }
        }
        return true;
    }

    // LINQ has one Sum with overloads for int, long and double selectors. Java can't pick between
    // overloads like that for a lambda such as p -> p.age, so each type gets its own name.
    // Like LINQ, the int and long sums throw on overflow instead of wrapping around.

    public int sumInt(ToIntFunction<? super T> selector) {
        Objects.requireNonNull(selector, "selector");
        int total = 0;
        for (T item : this) {
            total = Math.addExact(total, selector.applyAsInt(item));
        }
        return total;
    }

    public long sumLong(ToLongFunction<? super T> selector) {
        Objects.requireNonNull(selector, "selector");
        long total = 0;
        for (T item : this) {
            total = Math.addExact(total, selector.applyAsLong(item));
        }
        return total;
    }

    public double sumDouble(ToDoubleFunction<? super T> selector) {
        Objects.requireNonNull(selector, "selector");
        double total = 0;
        for (T item : this) {
            total += selector.applyAsDouble(item);
        }
        return total;
    }

    /** @throws NoSuchElementException if the query is empty */
    public <K extends Comparable<? super K>> K min(Function<? super T, ? extends K> selector) {
        return extreme(selector, Comparator.<K>naturalOrder());
    }

    /** @throws NoSuchElementException if the query is empty */
    public <K extends Comparable<? super K>> K max(Function<? super T, ? extends K> selector) {
        return extreme(selector, Comparator.<K>reverseOrder());
    }

    // The selected value that sorts first in the given order; the earliest one wins ties.
    private <K> K extreme(Function<? super T, ? extends K> selector, Comparator<? super K> order) {
        Objects.requireNonNull(selector, "selector");
        Iterator<T> it = iterator();
        if (!it.hasNext()) {
            throw new NoSuchElementException("query has no elements");
        }
        K best = selector.apply(it.next());
        while (it.hasNext()) {
            K value = selector.apply(it.next());
            if (order.compare(value, best) < 0) {
                best = value;
            }
        }
        return best;
    }
}
