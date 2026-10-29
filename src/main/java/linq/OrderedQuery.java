package linq;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The result of orderBy or orderByDescending. thenBy and thenByDescending add tie-breaking keys
 * to the same sort, rather than sorting again.
 */
public final class OrderedQuery<T> extends Query<T> {

    private final Query<T> unsorted;
    private final Comparator<T> order;

    OrderedQuery(Query<T> unsorted, Comparator<T> order) {
        // Sorting needs every element, so it happens when iteration starts. List.sort is
        // stable: elements with equal keys stay in their source order, as in LINQ.
        super(() -> {
            List<T> items = unsorted.toList();
            items.sort(order);
            return items.iterator();
        });
        this.unsorted = unsorted;
        this.order = order;
    }

    public <K extends Comparable<? super K>> OrderedQuery<T> thenBy(Function<? super T, ? extends K> key) {
        return new OrderedQuery<>(unsorted, order.thenComparing(OrderedQuery.<T, K>ascending(key)));
    }

    public <K extends Comparable<? super K>> OrderedQuery<T> thenByDescending(Function<? super T, ? extends K> key) {
        return new OrderedQuery<>(unsorted, order.thenComparing(OrderedQuery.<T, K>ascending(key).reversed()));
    }

    static <E, K extends Comparable<? super K>> Comparator<E> ascending(Function<? super E, ? extends K> key) {
        Objects.requireNonNull(key, "key");
        // LINQ sorts null keys before every other key.
        return Comparator.comparing(key, Comparator.nullsFirst(Comparator.<K>naturalOrder()));
    }
}
