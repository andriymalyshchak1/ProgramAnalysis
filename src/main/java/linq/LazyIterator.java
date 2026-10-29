package linq;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Base class for the iterators behind each query operator. A subclass only says how to
 * produce the next element; this class handles the hasNext/next bookkeeping.
 */
abstract class LazyIterator<T> implements Iterator<T> {

    private enum State { NOT_READY, READY, DONE }

    private State state = State.NOT_READY;
    private T next;

    /** Returns the next element, or {@code return endOfData();} when there are no more. */
    protected abstract T computeNext();

    protected final T endOfData() {
        state = State.DONE;
        return null;
    }

    @Override
    public final boolean hasNext() {
        if (state == State.NOT_READY) {
            next = computeNext();
            if (state != State.DONE) {
                state = State.READY;
            }
        }
        return state == State.READY;
    }

    @Override
    public final T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        state = State.NOT_READY;
        T result = next;
        next = null;
        return result;
    }
}
