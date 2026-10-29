package linq;

import static linq.TestData.PEOPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class AggregateTest {

    @Test
    void firstReturnsFirstElementOrFirstMatch() {
        assertEquals("Ana", PEOPLE.first().name());
        assertEquals("Ben", PEOPLE.first(p -> p.age() < 18).name());
    }

    @Test
    void firstThrowsWhenThereIsNothingToReturn() {
        assertThrows(NoSuchElementException.class, () -> Query.of().first());
        assertThrows(NoSuchElementException.class, () -> PEOPLE.first(p -> p.age() > 100));
    }

    @Test
    void anyAndAll() {
        assertTrue(PEOPLE.any());
        assertFalse(Query.of().any());
        assertTrue(PEOPLE.any(p -> p.age() > 40));
        assertFalse(PEOPLE.any(p -> p.age() > 50));
        assertTrue(PEOPLE.all(p -> p.age() >= 17));
        assertFalse(PEOPLE.all(p -> p.age() >= 18));
        assertTrue(Query.<Person>of().all(p -> false)); // vacuously true, as in LINQ
    }

    @Test
    void count() {
        assertEquals(5, PEOPLE.count());
        assertEquals(3, PEOPLE.where(p -> p.age() >= 18).count());
        assertEquals(0, Query.of().count());
    }

    @Test
    void sums() {
        assertEquals(135, PEOPLE.sumInt(Person::age));
        assertEquals(135L, PEOPLE.sumLong(Person::age));
        assertEquals(0.75, Query.of(0.5, 0.25).sumDouble(x -> x));
        assertEquals(0, Query.<Person>of().sumInt(Person::age));
    }

    @Test
    void sumIntThrowsOnOverflowLikeLinq() {
        assertThrows(ArithmeticException.class, () -> Query.of(Integer.MAX_VALUE, 1).sumInt(x -> x));
    }

    @Test
    void minAndMax() {
        assertEquals(17, PEOPLE.min(Person::age));
        assertEquals(45, PEOPLE.max(Person::age));
        assertEquals("Ana", PEOPLE.min(Person::name));
        assertEquals("Eli", PEOPLE.max(Person::name));
        assertThrows(NoSuchElementException.class, () -> Query.<Person>of().min(Person::age));
    }

    @Test
    void toListReturnsAnIndependentCopy() {
        List<Person> copy = PEOPLE.toList();
        copy.clear();
        assertEquals(5, PEOPLE.count());
    }
}
