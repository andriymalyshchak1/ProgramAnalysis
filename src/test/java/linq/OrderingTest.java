package linq;

import static linq.TestData.PEOPLE;
import static linq.TestData.names;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderingTest {

    // Three 4-letter words in an order that is neither alphabetical nor reverse alphabetical,
    // so plain stability, thenBy and thenByDescending each give a different result.
    private static final Query<String> FRUIT = Query.of("kiwi", "fig", "pear", "apple", "date");

    @Test
    void orderByIsStable() {
        // Ben and Dev are both 17, so they keep their source order.
        assertEquals(List.of("Ben", "Dev", "Cleo", "Ana", "Eli"), names(PEOPLE.orderBy(Person::age)));
    }

    @Test
    void orderByDescendingIsStableToo() {
        assertEquals(List.of("Eli", "Ana", "Cleo", "Ben", "Dev"), names(PEOPLE.orderByDescending(Person::age)));
    }

    @Test
    void thenByBreaksTies() {
        assertEquals(List.of("fig", "kiwi", "pear", "date", "apple"),
                FRUIT.orderBy(String::length).toList());
        assertEquals(List.of("fig", "date", "kiwi", "pear", "apple"),
                FRUIT.orderBy(String::length).thenBy(s -> s).toList());
        assertEquals(List.of("fig", "pear", "kiwi", "date", "apple"),
                FRUIT.orderBy(String::length).thenByDescending(s -> s).toList());
    }

    @Test
    void nullKeysSortFirstLikeLinq() {
        Query<String> words = Query.of("b", null, "a");
        assertEquals(Arrays.asList(null, "a", "b"), words.orderBy(s -> s).toList());
        assertEquals(Arrays.asList("b", "a", null), words.orderByDescending(s -> s).toList());
    }

    @Test
    void orderByOnEmptySource() {
        assertEquals(List.of(), Query.<String>of().orderBy(s -> s).toList());
    }
}
