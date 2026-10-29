package linq;

import java.util.List;

final class TestData {

    // Shared by many tests. That's safe because a query can be iterated any number of times.
    static final Query<Person> PEOPLE = Query.from(List.of(
            new Person("Ana", 34, "Austin"),
            new Person("Ben", 17, "Dallas"),
            new Person("Cleo", 22, "Austin"),
            new Person("Dev", 17, "Houston"),
            new Person("Eli", 45, "Dallas")));

    static List<String> names(Query<Person> people) {
        return people.select(Person::name).toList();
    }

    private TestData() {
    }
}
