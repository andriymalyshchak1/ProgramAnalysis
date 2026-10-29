package linq;

import static linq.TestData.PEOPLE;
import static linq.TestData.names;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FilterAndProjectTest {

    @Test
    void whereKeepsMatchingElementsInOrder() {
        assertEquals(List.of("Ana", "Cleo", "Eli"), names(PEOPLE.where(p -> p.age() >= 18)));
    }

    @Test
    void whereOnEmptySourceIsEmpty() {
        assertEquals(List.of(), Query.<Integer>of().where(x -> true).toList());
    }

    @Test
    void selectMapsEachElement() {
        assertEquals(List.of(3, 3, 4, 3, 3), PEOPLE.select(p -> p.name().length()).toList());
    }

    @Test
    void selectManyFlattensInOrder() {
        Query<List<Integer>> lists = Query.of(List.of(1, 2), List.of(), List.of(3));
        assertEquals(List.of(1, 2, 3), lists.selectMany(xs -> xs).toList());
    }

    @Test
    void takeAndSkip() {
        Query<Integer> numbers = Query.of(1, 2, 3, 4, 5);
        assertEquals(List.of(1, 2), numbers.take(2).toList());
        assertEquals(List.of(4, 5), numbers.skip(3).toList());
        assertEquals(List.of(2, 3), numbers.skip(1).take(2).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), numbers.take(10).toList());
        assertEquals(List.of(), numbers.skip(10).toList());
        assertEquals(List.of(), numbers.take(0).toList());
        assertEquals(List.of(), numbers.take(-1).toList());
    }

    @Test
    void distinctKeepsFirstOccurrences() {
        assertEquals(List.of(3, 1, 2), Query.of(3, 1, 3, 2, 1).distinct().toList());
    }

    @Test
    void operatorsChain() {
        Query<String> adultCities = PEOPLE.where(p -> p.age() >= 18).select(Person::city).distinct();
        assertEquals(List.of("Austin", "Dallas"), adultCities.toList());
    }
}
