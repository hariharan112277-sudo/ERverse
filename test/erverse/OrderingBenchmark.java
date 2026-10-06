package erverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Evidence for Table 6.1 / Section 6.3 of the report: the heap-based triage
 * engine measured against the naive baseline the report says was benchmarked -
 * an {@code ArrayList} re-sorted on every insertion.
 *
 * <p>This is not a JUnit test (there is nothing to assert); it is a runnable
 * harness that prints a table. Run it with:</p>
 *
 * <pre>
 * javac -d bin src/erverse/*.java test/erverse/OrderingBenchmark.java
 * java -cp bin erverse.OrderingBenchmark
 * </pre>
 *
 * <p>It deliberately calls neither {@link TriageSystem} nor
 * {@link DatabaseManager}: persistence would dominate the timing and hide the
 * data-structure cost that the report is actually discussing.</p>
 */
public class OrderingBenchmark {

    private static final int[] SIZES = {5, 50, 500, 5_000, 50_000};
    private static final int REPEATS = 5;

    public static void main(String[] args) {
        System.out.println("ERverse - ordering data structure benchmark");
        System.out.println("(severity rank first, arrival timestamp second, patient id last)");
        System.out.println();
        System.out.printf("%10s | %18s | %18s | %12s%n",
                "queue size", "PriorityQueue total", "re-sorted ArrayList", "speed-up");
        System.out.println("-----------|--------------------|--------------------|-------------");

        for (int size : SIZES) {
            long heapNanos = bestOf(size, OrderingBenchmark::enqueueDequeueWithHeap);
            long listNanos = bestOf(size, OrderingBenchmark::enqueueDequeueWithSortedList);
            double speedUp = listNanos / (double) heapNanos;
            System.out.printf("%10d | %15.3f ms | %15.3f ms | %10.2fx%n",
                    size, heapNanos / 1e6, listNanos / 1e6, speedUp);
        }

        System.out.println();
        System.out.println("Per-operation cost (n registrations + n drains, divide by 2n):");
        System.out.printf("%10s | %16s | %16s%n", "queue size", "heap per op", "ArrayList per op");
        System.out.println("-----------|------------------|------------------");
        for (int size : SIZES) {
            long heapNanos = bestOf(size, OrderingBenchmark::enqueueDequeueWithHeap);
            long listNanos = bestOf(size, OrderingBenchmark::enqueueDequeueWithSortedList);
            System.out.printf("%10d | %13.1f ns | %13.1f ns%n",
                    size, heapNanos / (double) (2 * size), listNanos / (double) (2 * size));
        }

        System.out.println();
        System.out.println("Load factor (cost at 50 000 patients / cost at 5 patients).");
        System.out.println("O(log n) should grow slowly; O(n log n) per operation grows sharply.");
    }

    private interface Workload {
        void run(int size);
    }

    /** Runs the workload REPEATS times on a fresh heap and keeps the fastest run. */
    private static long bestOf(int size, Workload workload) {
        for (int warmUp = 0; warmUp < 2; warmUp++) {
            workload.run(size);               // let the JIT compile the hot path
        }
        long best = Long.MAX_VALUE;
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            long start = System.nanoTime();
            workload.run(size);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    /** Registers `size` patients into a PriorityQueue, then drains it in triage order. */
    private static void enqueueDequeueWithHeap(int size) {
        Random random = new Random(42);
        PriorityQueue<Patient> heap = new PriorityQueue<>();
        for (int i = 0; i < size; i++) {
            heap.add(sample(random, i));
        }
        long checksum = 0;
        while (!heap.isEmpty()) {
            checksum += heap.poll().getPatientId();
        }
        consume(checksum);
    }

    /**
     * The baseline: an ArrayList that is fully re-sorted after every insertion.
     * This is what "manual re-scanning of a growing list" costs in practice.
     */
    private static void enqueueDequeueWithSortedList(int size) {
        Random random = new Random(42);
        List<Patient> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(sample(random, i));
            list.sort(Comparator.naturalOrder());      // O(n log n) on every registration
        }
        long checksum = 0;
        while (!list.isEmpty()) {
            checksum += list.remove(0).getPatientId(); // and O(n) to take the head
        }
        consume(checksum);
    }

    private static Patient sample(Random random, int index) {
        Priority priority = Priority.values()[random.nextInt(Priority.values().length)];
        return new Patient("Patient " + index, 20 + (index % 70), "benchmark", priority);
    }

    /** Prevents the JIT from eliminating the loop as dead code. */
    private static void consume(long checksum) {
        if (checksum == Long.MIN_VALUE) {
            System.out.println(checksum);
        }
    }
}
