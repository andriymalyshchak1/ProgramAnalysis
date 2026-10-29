package linq;

import static linq.TestData.PEOPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DeferredExecutionTest {

    @Test
    void buildingAQueryRunsNothing() {
        AtomicInteger calls = new AtomicInteger();
        Query<Integer> evens = Query.of(1, 2, 3, 4).where(x -> {
            calls.incrementAndGet();
            return x % 2 == 0;
        });
        assertEquals(0, calls.get());

        assertEquals(List.of(2, 4), evens.toList());
        assertEquals(4, calls.get());
    }

    @Test
    void elementsArePulledOnlyAsNeeded() {
        AtomicInteger calls = new AtomicInteger();
        Query<Integer> evens = Query.of(1, 2, 3, 4).where(x -> {
            calls.incrementAndGet();
            return x % 2 == 0;
        });
        assertEquals(2, evens.first());
        assertEquals(2, calls.get()); // stopped at 2; never looked at 3 or 4
    }

    @Test
    void takeStopsPullingOnceItHasEnough() {
        AtomicInteger calls = new AtomicInteger();
        Query<Integer> firstTwo = Query.of(1, 2, 3, 4, 5).where(x -> calls.incrementAndGet() > 0).take(2);
        assertEquals(List.of(1, 2), firstTwo.toList());
        assertEquals(2, calls.get());
    }

    @Test
    void worksOnAnInfiniteSource() {
        Iterable<Integer> naturals = () -> new Iterator<Integer>() {
            private int next = 0;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Integer next() {
                return next++;
            }
        };
        Query<Integer> squaresOfEvens = Query.from(naturals).where(x -> x % 2 == 0).select(x -> x * x).take(3);
        assertEquals(List.of(0, 4, 16), squaresOfEvens.toList());
    }

    @Test
    void iteratingAgainSeesChangesToTheSource() {
        List<String> names = new ArrayList<>(List.of("Ana", "Ben"));
        Query<String> bNames = Query.from(names).where(s -> s.startsWith("B"));
        assertEquals(List.of("Ben"), bNames.toList());

        names.add("Bo");
        assertEquals(List.of("Ben", "Bo"), bNames.toList());
    }

    @Test
    void unlikeAStreamAQueryCanBeIteratedAgain() {
        Stream<Integer> stream = Stream.of(1, 2, 3).filter(x -> x > 1);
        assertEquals(List.of(2, 3), stream.toList());
        assertThrows(IllegalStateException.class, stream::toList);

        Query<Integer> query = Query.of(1, 2, 3).where(x -> x > 1);
        assertEquals(List.of(2, 3), query.toList());
        assertEquals(List.of(2, 3), query.toList());
    }

    @Test
    void bufferingOperatorsAreDeferredToo() {
        List<Integer> numbers = new ArrayList<>(List.of(3, 1, 2));
        Query<Integer> sorted = Query.from(numbers).orderBy(x -> x);
        Query<Integer> groupSizes = Query.from(numbers).groupBy(x -> x % 2).select(Grouping::count);
        Query<Integer> matched = Query.of(0, 1).join(numbers, x -> x, x -> x, (a, b) -> a);

        numbers.add(0);

        assertEquals(List.of(0, 1, 2, 3), sorted.toList());
        assertEquals(List.of(2, 2), groupSizes.toList()); // odd: 3, 1   even: 2, 0
        assertEquals(List.of(0, 1), matched.toList());
    }

    @Test
    void distinctStartsFreshEachIteration() {
        Query<Integer> unique = Query.of(1, 1, 2).distinct();
        assertEquals(List.of(1, 2), unique.toList());
        assertEquals(List.of(1, 2), unique.toList());
    }

    @Test
    void argumentsAreCheckedRightAway() {
        // Execution is deferred, but a null argument fails when the query is built, as in LINQ.
        assertThrows(NullPointerException.class, () -> PEOPLE.where(null));
        assertThrows(NullPointerException.class, () -> PEOPLE.select(null));
    }
}
