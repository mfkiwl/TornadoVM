package uk.ac.manchester.tornado.unittests.arrays;

import org.junit.Test;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;
import uk.ac.manchester.tornado.unittests.common.TornadoTestBase;

import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;

/**
 * How to test?
 *
 * <p>
 * <code>
 * tornado-test -V --fast uk.ac.manchester.tornado.unittests.arrays.TestArrayCopies
 * </code>
 * </p>
 */
public class TestArrayCopies extends TornadoTestBase {
    // CHECKSTYLE:OFF
    public static void intPrivateCopy (IntArray a, IntArray b) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            int[] arrayA = new int[128];
            int[] arrayB = new int[128];
            for (int j = 0; j < 128; j++) {
                arrayA[j] = j;
                arrayB[j] = 2;
            }
            if ((a.get(i) % 2) == 0 ) {
                arrayB = arrayA;
            }
            b.set(i, arrayB[i]);
        }
    }

    public static void floatPrivateCopy (FloatArray a, FloatArray b) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            float[] arrayA = new float[128];
            float[] arrayB = new float[128];
            for (int j = 0; j < 128; j++) {
                arrayA[j] = j;
                arrayB[j] = 2;
            }
            if (a.get(i) == 1) {
                arrayB = arrayA;
            }
            b.set(i, arrayB[i]);
        }
    }

    public static void doublePrivateCopy (DoubleArray a, DoubleArray b) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            double[] arrayA = new double[128];
            double[] arrayB = new double[128];
            for (int j = 0; j < 128; j++) {
                arrayA[j] = j;
                arrayB[j] = 2;
            }
            double variable = a.get(i) * 2.0;
            if (variable < 0.3) {
                arrayB = arrayA;
            }
            b.set(i, arrayB[i]);
        }
    }

    public static void longPrivateCopy (LongArray a, LongArray b) {
        for (@Parallel int i = 0; i < a.getSize(); i++) {
            long[] arrayA = new long[128];
            long[] arrayB = new long[128];
            for (int j = 0; j < 128; j++) {
                arrayA[j] = j;
                arrayB[j] = 2;
            }
            if ((a.get(i) % 2) == 0 ) {
                arrayB = arrayA;
            }
            b.set(i, arrayB[i]);
        }
    }

    @Test
    public void testPrivateArrayCopyInt() {
        final int numElements = 16;

        IntArray a = new IntArray(numElements);
        IntArray b = new IntArray(numElements);
        IntArray c = new IntArray(numElements);

        Random r = new Random();
        IntStream.range(0, numElements).sequential().forEach(i -> {
            a.set(i, r.nextInt());
        });

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a) //
                .task("t0", TestArrayCopies::intPrivateCopy, a, b) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, b);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
        executionPlan.execute();

        intPrivateCopy(a, c);

        for (int i = 0; i < numElements; i++) {
            assertEquals(b.get(i), c.get(i));
        }

    }

    @Test
    public void testPrivateArrayCopyFloat() {
        final int numElements = 16;

        FloatArray a = new FloatArray(numElements);
        FloatArray b = new FloatArray(numElements);
        FloatArray c = new FloatArray(numElements);

        Random r = new Random();
        IntStream.range(0, numElements).sequential().forEach(i -> {
            a.set(i, r.nextFloat());
        });

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a) //
                .task("t0", TestArrayCopies::floatPrivateCopy, a, b) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, b);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
        executionPlan.execute();

        floatPrivateCopy(a, c);

        for (int i = 0; i < numElements; i++) {
            assertEquals(b.get(i), c.get(i), 0.01f);
        }

    }

    @Test
    public void testPrivateArrayCopyDouble() {
        final int numElements = 16;

        DoubleArray a = new DoubleArray(numElements);
        DoubleArray b = new DoubleArray(numElements);
        DoubleArray c = new DoubleArray(numElements);

        Random r = new Random();
        IntStream.range(0, numElements).sequential().forEach(i -> {
            a.set(i, r.nextDouble());
        });

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a) //
                .task("t0", TestArrayCopies::doublePrivateCopy, a, b) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, b);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
        executionPlan.execute();

        doublePrivateCopy(a, c);

        for (int i = 0; i < numElements; i++) {
            assertEquals(b.get(i), c.get(i), 0.01);
        }

    }

    @Test
    public void testPrivateArrayCopyLong() {
        final int numElements = 16;

        LongArray a = new LongArray(numElements);
        LongArray b = new LongArray(numElements);
        LongArray c = new LongArray(numElements);

        Random r = new Random();
        IntStream.range(0, numElements).sequential().forEach(i -> {
            a.set(i, r.nextLong());
        });

        TaskGraph taskGraph = new TaskGraph("s0") //
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a) //
                .task("t0", TestArrayCopies::longPrivateCopy, a, b) //
                .transferToHost(DataTransferMode.EVERY_EXECUTION, b);

        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
        TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph);
        executionPlan.execute();

        longPrivateCopy(a, c);

        for (int i = 0; i < numElements; i++) {
            assertEquals(b.get(i), c.get(i));
        }

    }

    // CHECKSTYLE:ON
}
