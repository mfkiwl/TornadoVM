/*
 * Copyright (c) 2013-2020, 2022, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package uk.ac.manchester.tornado.benchmarks.sgemv;

import static uk.ac.manchester.tornado.api.collections.math.TornadoMath.findULPDistance;
import static uk.ac.manchester.tornado.benchmarks.LinearAlgebraArrays.sgemv;

import java.util.Random;

import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutor;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.benchmarks.BenchmarkDriver;
import uk.ac.manchester.tornado.benchmarks.LinearAlgebraArrays;

/**
 * <p>
 * How to run?
 * </p>
 * <code>
 *     tornado -m tornado.benchmarks/uk.ac.manchester.tornado.benchmarks.BenchmarkRunner sgemv
 * </code>
 */
public class SgemvTornado extends BenchmarkDriver {

    private final int m;
    private final int n;
    private float[] a;
    private float[] x;
    private float[] y;

    public SgemvTornado(int iterations, int m, int n) {
        super(iterations);
        this.m = m;
        this.n = n;
    }

    @Override
    public void setUp() {
        a = new float[m * n];
        x = new float[n];
        y = new float[n];

        final Random random = new Random();

        for (int i = 0; i < m; i++) {
            a[i * (m + 1)] = 1;
        }

        for (int i = 0; i < n; i++) {
            x[i] = random.nextFloat();
        }

        taskGraph = new TaskGraph("benchmark") //
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, x) //
                .task("sgemv", LinearAlgebraArrays::sgemv, m, n, a, x, y) //
                .transferToHost(y);
        immutableTaskGraph = taskGraph.snapshot();
        executor = new TornadoExecutor(immutableTaskGraph).build();
        executor.withWarmUp();
    }

    @Override
    public void tearDown() {
        executor.dumpProfiles();

        a = null;
        x = null;
        y = null;

        executor.resetDevices();
        super.tearDown();
    }

    @Override
    public void benchmarkMethod(TornadoDevice device) {
        executor.withDevice(device).execute();
    }

    @Override
    public boolean validate(TornadoDevice device) {

        final float[] result = new float[n];

        benchmarkMethod(device);
        executor.transferToHost(y).clearProfiles();

        sgemv(m, n, a, x, result);

        final float ulp = findULPDistance(y, result);
        return ulp < MAX_ULP;
    }

    public void printSummary() {
        if (isValid()) {
            System.out.printf("id=%s, elapsed=%f, per iteration=%f\n", getProperty("benchmark.device"), getElapsed(), getElapsedPerIteration());
        } else {
            System.out.printf("id=%s produced invalid result\n", getProperty("benchmark.device"));
        }
    }

}
