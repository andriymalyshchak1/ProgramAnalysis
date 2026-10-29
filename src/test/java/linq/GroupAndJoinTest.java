package linq;

import static linq.TestData.PEOPLE;
import static linq.TestData.names;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupAndJoinTest {

    record Pet(String name, String owner) {
    }

    @Test
    void groupByKeepsKeysInFirstSeenOrder() {
        Query<String> groups = PEOPLE.groupBy(Person::city).select(g -> g.key() + "=" + names(g));
        assertEquals(List.of("Austin=[Ana, Cleo]", "Dallas=[Ben, Eli]", "Houston=[Dev]"), groups.toList());
    }

    @Test
    void groupByWithElementSelector() {
        Query<String> groups = PEOPLE.groupBy(Person::city, Person::name).select(g -> g.key() + "=" + g.toList());
        assertEquals(List.of("Austin=[Ana, Cleo]", "Dallas=[Ben, Eli]", "Houston=[Dev]"), groups.toList());
    }

    @Test
    void groupsAreQueries() {
        Query<String> adultsPerCity = PEOPLE.groupBy(Person::city)
                .select(g -> g.key() + ":" + g.where(p -> p.age() >= 18).count());
        assertEquals(List.of("Austin:2", "Dallas:1", "Houston:0"), adultsPerCity.toList());
    }

    @Test
    void nullKeyFormsItsOwnGroup() {
        Query<Grouping<Character, String>> groups =
                Query.of("apple", null, "avocado").groupBy(s -> s == null ? null : s.charAt(0));
        assertEquals(Arrays.asList('a', null), groups.select(Grouping::key).toList());
        assertEquals(List.of(2, 1), groups.select(Grouping::count).toList());
    }

    @Test
    void joinPairsMatchesInOuterThenInnerOrder() {
        List<Pet> pets = List.of(
                new Pet("Rex", "Ana"), new Pet("Tom", "Eli"), new Pet("Bo", "Ana"), new Pet("Zed", "Nobody"));
        Query<String> owned = PEOPLE.join(pets, Person::name, Pet::owner,
                (person, pet) -> person.name() + "-" + pet.name());
        assertEquals(List.of("Ana-Rex", "Ana-Bo", "Eli-Tom"), owned.toList());
    }

    @Test
    void joinNeverMatchesNullKeys() {
        Query<String> joined = Query.of("x", null).join(Arrays.asList(null, "x"), s -> s, s -> s, (a, b) -> a + b);
        assertEquals(List.of("xx"), joined.toList());
    }
}
