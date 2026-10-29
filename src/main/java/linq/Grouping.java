package linq;

import java.util.Collections;
import java.util.List;

/**
 * One group from groupBy: a key plus the elements that had that key, in source order.
 * A group is itself a query, so {@code g.count()} or {@code g.where(...)} work on it.
 */
public final class Grouping<K, T> extends Query<T> {

    private final K key;

    Grouping(K key, List<T> elements) {
        super(Collections.unmodifiableList(elements));
        this.key = key;
    }

    public K key() {
        return key;
    }
}
