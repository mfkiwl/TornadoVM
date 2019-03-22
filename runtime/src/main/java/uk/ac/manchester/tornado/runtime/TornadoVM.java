/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.runtime;

import static uk.ac.manchester.tornado.api.enums.TornadoExecutionStatus.COMPLETE;
import static uk.ac.manchester.tornado.runtime.common.Tornado.ENABLE_PROFILING;
import static uk.ac.manchester.tornado.runtime.common.Tornado.PRINT_COMPILE_TIMES;
import static uk.ac.manchester.tornado.runtime.common.Tornado.USE_VM_FLUSH;
import static uk.ac.manchester.tornado.runtime.common.Tornado.VM_USE_DEPS;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.common.TornadoEvents;
import uk.ac.manchester.tornado.api.exceptions.TornadoException;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.runtime.common.CallStack;
import uk.ac.manchester.tornado.runtime.common.DeviceObjectState;
import uk.ac.manchester.tornado.runtime.common.TornadoAcceleratorDevice;
import uk.ac.manchester.tornado.runtime.common.TornadoInstalledCode;
import uk.ac.manchester.tornado.runtime.common.TornadoLogger;
import uk.ac.manchester.tornado.runtime.common.TornadoOptions;
import uk.ac.manchester.tornado.runtime.graph.TornadoExecutionContext;
import uk.ac.manchester.tornado.runtime.graph.TornadoGraphAssembler.TornadoVMBytecodes;
import uk.ac.manchester.tornado.runtime.tasks.GlobalObjectState;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

/**
 * TornadoVM: it includes a bytecode interpreter (Tornado bytecodes), a memory
 * manager for all devices (FPGAs, GPUs and multi-core that follows the OpenCL
 * programming model), and a JIT compiler from Java bytecode to OpenCL.
 *
 * The JIT compiler extends the Graal JIT Compiler for OpenCL compilation.
 * 
 * There is an instance of the {@link TornadoVM} per
 * {@link TornadoTaskSchedule}. Each TornadoVM contains the logic to orchestrate
 * the execution on the parallel device (e.g., a GPU).
 *
 */
public class TornadoVM extends TornadoLogger {

    private static final Event EMPTY_EVENT = new EmptyEvent();

    private static final int MAX_EVENTS = 32;
    private final boolean useDependencies;

    private final TornadoExecutionContext graphContext;
    private final List<Object> objects;
    private final GlobalObjectState[] globalStates;
    private final CallStack[] stacks;
    private final int[][] events;
    private final int[] eventsIndicies;
    private final List<TornadoAcceleratorDevice> contexts;
    private final TornadoInstalledCode[] installedCodes;

    private final List<Object> constants;
    private final List<SchedulableTask> tasks;

    private final ByteBuffer buffer;

    private double totalTime;
    private long invocations;

    public TornadoVM(TornadoExecutionContext graphContext, byte[] code, int limit) {

        this.graphContext = graphContext;

        useDependencies = graphContext.meta().enableOooExecution() | VM_USE_DEPS;
        totalTime = 0;
        invocations = 0;

        buffer = ByteBuffer.wrap(code);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.limit(limit);

        debug("loading tornado vm...");

        TornadoInternalError.guarantee(buffer.get() == TornadoVMBytecodes.SETUP.index(), "invalid code");
        contexts = graphContext.getDevices();
        buffer.getInt();
        int taskCount = buffer.getInt();
        stacks = graphContext.getFrames();
        events = new int[buffer.getInt()][MAX_EVENTS];
        eventsIndicies = new int[events.length];

        installedCodes = new TornadoInstalledCode[taskCount];

        for (int i = 0; i < events.length; i++) {
            Arrays.fill(events[i], -1);
            eventsIndicies[i] = 0;
        }

        debug("found %d contexts", contexts.size());
        debug("created %d stacks", stacks.length);
        debug("created %d event lists", events.length);

        objects = graphContext.getObjects();
        globalStates = new GlobalObjectState[objects.size()];
        debug("fetching %d object states...", globalStates.length);
        for (int i = 0; i < objects.size(); i++) {
            final Object object = objects.get(i);
            TornadoInternalError.guarantee(object != null, "null object found in TornadoVM");
            globalStates[i] = TornadoCoreRuntime.getTornadoRuntime().resolveObject(object);
            debug("\tobject[%d]: [0x%x] %s %s", i, object.hashCode(), object.getClass().getTypeName(), globalStates[i]);
        }

        byte op = buffer.get();
        while (op != TornadoVMBytecodes.BEGIN.index()) {
            TornadoInternalError.guarantee(op == TornadoVMBytecodes.CONTEXT.index(), "invalid code: 0x%x", op);
            final int deviceIndex = buffer.getInt();
            debug("loading context %s", contexts.get(deviceIndex));
            final long t0 = System.nanoTime();
            contexts.get(deviceIndex).ensureLoaded();
            final long t1 = System.nanoTime();
            debug("loaded in %.9f s", (t1 - t0) * 1e-9);
            op = buffer.get();
        }

        constants = graphContext.getConstants();
        tasks = graphContext.getTasks();

        debug("%s - vm ready to go", graphContext.getId());
        buffer.mark();
    }

    private GlobalObjectState resolveGlobalObjectState(int index) {
        return globalStates[index];
    }

    private DeviceObjectState resolveObjectState(int index, int device) {
        return globalStates[index].getDeviceState(contexts.get(device));
    }

    private CallStack resolveStack(int index, int numArgs, CallStack[] stacks, TornadoAcceleratorDevice device, boolean setNewDevice) {
        if (graphContext.meta().isDebug() && setNewDevice) {
            debug("Recompiling task on device " + device);
        }
        if (stacks[index] == null || setNewDevice) {
            stacks[index] = device.createStack(numArgs);
        }
        return stacks[index];
    }

    public void invalidateObjects() {
        for (GlobalObjectState globalState : globalStates) {
            globalState.invalidate();
        }
    }

    public void warmup() {
        execute(true);
    }

    public void compile() {
        execute(true);
    }

    public Event execute() {
        return execute(false);
    }

    public Event executeBatches() {
        return executeBatches(false);
    }

    private Event executeBatches(boolean isWarmup) {

        final long t0 = System.nanoTime();
        int lastEvent = -1;
        for (int[] waitList : events) {
            Arrays.fill(waitList, -1);
        }
        StringBuilder bytecodesList = new StringBuilder();

        while (buffer.hasRemaining()) {
            final byte op = buffer.get();
            if (op == TornadoVMBytecodes.ALLOCATE_BATCH.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final long offset = buffer.getLong(); // << XXX We may not need
                                                      // offset
                final long sizeBatch = buffer.getLong();

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                String verbose = String.format("vm: ALLOCATE_BATCH [0x%x] %s on %s, size=%d", object.hashCode(), object, device, sizeBatch);
                if (graphContext.meta().isDebug()) {
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);
                lastEvent = device.ensureAllocated(object, objectState);

            } else if (op == TornadoVMBytecodes.COPYIN_BATCH.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                String verbose = String.format("vm: COPY_IN_BATCH [Object Hash Code=0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset,
                        eventList);
                if (graphContext.meta().isDebug()) {
                    debug("vm: state=%s", objectState);
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                lastEvent = device.ensurePresent(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }

            } else if (op == TornadoVMBytecodes.STREAM_IN_BATCH.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                String verbose = String.format("vm: STREAM_IN_BATCH [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                if (graphContext.meta().isDebug()) {
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: state=%s", objectState);
                }

                lastEvent = device.streamIn(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.STREAM_OUT_BATCH.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                String verbose = String.format("vm: STREAM_OUT_BATCH [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                if (graphContext.meta().isDebug()) {
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                lastEvent = device.streamOut(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.STREAM_OUT_BLOCKING_BATCH.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                String verbose = String.format("vm: STREAM_OUT_BLOCKING_BATCH [0x%x] %s on %s, size=%d, offset=%d [event list=%d]", object.hashCode(), object, device, sizeBatch, offset, eventList);
                if (graphContext.meta().isDebug()) {
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                lastEvent = device.streamOut(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }

            } else if (op == TornadoVMBytecodes.LAUNCH_BATCH.index()) {
                final int gtid = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int taskIndex = buffer.getInt();
                final int numArgs = buffer.getInt();
                final int eventList = buffer.getInt();

                final long offset = buffer.getLong();
                final long sizeBatch = buffer.getLong();

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                boolean redeployOnDevice = graphContext.redeployOnDevice();

                final CallStack stack = resolveStack(gtid, numArgs, stacks, device, redeployOnDevice);

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;
                final SchedulableTask task = tasks.get(taskIndex);

                String verbose = String.format("vm: LAUNCH_BATCH %s on %s, size=%d, offset=%d [event list=%d]", task.getName(), contexts.get(contextIndex), sizeBatch, offset, eventList);
                if (graphContext.meta().isDebug()) {
                    debug(verbose);
                }
                bytecodesList.append(verbose + "\n");

                if (installedCodes[taskIndex] == null) {
                    final long compileStart = System.nanoTime();
                    task.mapTo(device);
                    try {
                        installedCodes[taskIndex] = device.installCode(task);
                    } catch (Error | Exception e) {
                        fatal("unable to compile task %s", task.getName());
                        debug(e.getMessage());
                    }
                    final long compileEnd = System.nanoTime();
                    if (graphContext.meta().shouldPrintCompileTimes()) {
                        if (PRINT_COMPILE_TIMES) {
                            System.out.printf("compile: " + task.getName() + "  " + +(compileEnd - compileStart) + "ns" + "\n");
                        }
                    }

                    if (graphContext.meta().isDebug()) {
                        debug("vm: compiled in %.9f s", (compileEnd - compileStart) * 1e-9);
                    }
                }

                if (isWarmup) {
                    popArgumentsFromStack(numArgs);
                    continue;
                }

                final TornadoInstalledCode installedCode = installedCodes[taskIndex];
                final Access[] accesses = task.getArgumentsAccess();

                if (redeployOnDevice || !stack.isOnDevice()) {
                    stack.reset();
                }
                for (int i = 0; i < numArgs; i++) {
                    final byte argType = buffer.get();
                    final int argIndex = buffer.getInt();

                    if (stack.isOnDevice()) {
                        continue;
                    }

                    if (argType == TornadoVMBytecodes.CONSTANT_ARG.index()) {
                        stack.push(constants.get(argIndex));
                    } else if (argType == TornadoVMBytecodes.REFERENCE_ARG.index()) {
                        final GlobalObjectState globalState = resolveGlobalObjectState(argIndex);
                        final DeviceObjectState objectState = globalState.getDeviceState(contexts.get(contextIndex));

                        TornadoInternalError.guarantee(objectState.isValid(), "object is not valid: %s %s", objects.get(argIndex), objectState);

                        stack.push(objects.get(argIndex), objectState);
                        if (accesses[i] == Access.WRITE || accesses[i] == Access.READ_WRITE) {
                            globalState.setOwner(device);
                            objectState.setContents(true);
                            objectState.setModified(true);
                        }
                    } else {
                        TornadoInternalError.shouldNotReachHere();
                    }
                }

                TaskMetaData metadata = null;
                if (task.meta() instanceof TaskMetaData) {
                    metadata = (TaskMetaData) task.meta();
                } else {
                    throw new RuntimeException("task.meta is not instanceof TaskMetada");
                }

                if (useDependencies) {
                    lastEvent = installedCode.launchWithDeps(stack, metadata, waitList);
                } else {
                    lastEvent = installedCode.launchWithoutDeps(stack, metadata);
                }
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.ADD_DEP.index()) {
                final int eventList = buffer.getInt();
                if (isWarmup) {
                    continue;
                }
                if (useDependencies && lastEvent != -1) {
                    String verbose = String.format("vm: ADD_DEP %s to event list %d", lastEvent, eventList);
                    if (graphContext.meta().isDebug()) {
                        debug(verbose);
                    }
                    bytecodesList.append(verbose + "\n");

                    TornadoInternalError.guarantee(eventsIndicies[eventList] < events[eventList].length, "event list is too small");
                    events[eventList][eventsIndicies[eventList]] = lastEvent;
                    eventsIndicies[eventList]++;
                }

            } else if (op == TornadoVMBytecodes.BARRIER.index()) {
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                if (graphContext.meta().isDebug()) {
                    debug("vm: BARRIER event list %d", eventList);
                }
                bytecodesList.append(String.format("BARRIER event list %d\n", eventList));

                if (contexts.size() == 1) {
                    final TornadoAcceleratorDevice device = contexts.get(0);
                    lastEvent = device.enqueueMarker(waitList);
                } else if (contexts.size() > 1) {
                    TornadoInternalError.shouldNotReachHere("unimplemented multi-context barrier");
                }

                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.END.index()) {
                if (graphContext.meta().isDebug()) {
                    debug("vm: END");
                }
                bytecodesList.append(String.format("END\n"));

                break;
            } else {
                if (graphContext.meta().isDebug()) {
                    debug("vm: invalid op 0x%x(%d)", op, op);
                }
                throw new TornadoException("[ERROR] TornadoVM Bytecode not recognized");
            }
        }

        Event barrier = EMPTY_EVENT;
        if (!isWarmup) {
            for (TornadoAcceleratorDevice dev : contexts) {
                if (useDependencies) {
                    final int event = dev.enqueueMarker();
                    barrier = dev.resolveEvent(event);
                }

                if (USE_VM_FLUSH) {
                    dev.flush();
                }
            }
        }

        final long t1 = System.nanoTime();
        final double elapsed = (t1 - t0) * 1e-9;
        if (!isWarmup) {
            totalTime += elapsed;
            invocations++;
        }

        if (graphContext.meta().isDebug()) {
            debug("vm: complete elapsed=%.9f s (%d iterations, %.9f s mean)", elapsed, invocations, (totalTime / invocations));
        }

        buffer.reset();

        if (TornadoOptions.printBytecodes) {
            System.out.println(bytecodesList.toString());
        }

        return barrier;
    }

    private void popArgumentsFromStack(int numArgs) {
        for (int i = 0; i < numArgs; i++) {
            buffer.get();
            buffer.getInt();
        }
    }

    private Event execute(boolean isWarmup) {

        final long t0 = System.nanoTime();
        int lastEvent = -1;
        for (int[] waitList : events) {
            Arrays.fill(waitList, -1);
        }
        StringBuilder bytecodesList = new StringBuilder();

        while (buffer.hasRemaining()) {
            final byte op = buffer.get();

            if (op == TornadoVMBytecodes.ALLOCATE.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: ALLOCATE [0x%x] %s on %s", object.hashCode(), object, device);
                }
                bytecodesList.append(String.format("ALLOCATE [0x%x] %s on %s\n", object.hashCode(), object, device));

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);
                lastEvent = device.ensureAllocated(object, objectState);

            } else if (op == TornadoVMBytecodes.COPY_IN.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: COPY_IN [0x%x] %s on %s [event list=%d]", object.hashCode(), object, device, eventList);
                }
                bytecodesList.append(String.format("COPY_IN [0x%x] %s on %s [event list=%d]\n", object.hashCode(), object, device, eventList));

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: state=%s", objectState);
                }

                lastEvent = device.ensurePresent(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }

            } else if (op == TornadoVMBytecodes.STREAM_IN.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);
                if (graphContext.meta().isDebug()) {
                    debug("vm: STREAM_IN [0x%x] %s on %s [event list=%d]", object.hashCode(), object, device, eventList);
                }
                bytecodesList.append(String.format("STREAM_IN [0x%x] %s on %s [event list=%d]\n", object.hashCode(), object, device, eventList));

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: state=%s", objectState);
                }

                lastEvent = device.streamIn(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.STREAM_OUT.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: STREAM_OUT [0x%x] %s on %s [event list=%d]", object.hashCode(), object, device, eventList);
                }
                bytecodesList.append(String.format("STREAM_OUT [0x%x] %s on %s [event list=%d]\n", object.hashCode(), object, device, eventList));

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                lastEvent = device.streamOut(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.STREAM_OUT_BLOCKING.index()) {
                final int objectIndex = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                final Object object = objects.get(objectIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: STREAM_OUT_BLOCKING [0x%x] %s on %s [event list=%d]", object.hashCode(), object, device, eventList);
                }
                bytecodesList.append(String.format("STREAM_OUT_BLOCKING [0x%x] %s on %s [event list=%d]\n", object.hashCode(), object, device, eventList));

                final DeviceObjectState objectState = resolveObjectState(objectIndex, contextIndex);

                lastEvent = device.streamOut(object, objectState, waitList);
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.LAUNCH.index()) {
                final int gtid = buffer.getInt();
                final int contextIndex = buffer.getInt();
                final int taskIndex = buffer.getInt();
                final int numArgs = buffer.getInt();
                final int eventList = buffer.getInt();

                final TornadoAcceleratorDevice device = contexts.get(contextIndex);
                boolean redeployOnDevice = graphContext.redeployOnDevice();

                final CallStack stack = resolveStack(gtid, numArgs, stacks, device, redeployOnDevice);

                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;
                final SchedulableTask task = tasks.get(taskIndex);

                if (graphContext.meta().isDebug()) {
                    debug("vm: LAUNCH %s on %s [event list=%d]", task.getName(), contexts.get(contextIndex), eventList);
                }
                bytecodesList.append(String.format("LAUNCH %s on %s [event list=%d]\n", task.getName(), contexts.get(contextIndex), eventList));

                if (installedCodes[taskIndex] == null) {
                    final long compileStart = System.nanoTime();
                    task.mapTo(device);
                    try {
                        installedCodes[taskIndex] = device.installCode(task);
                    } catch (Error | Exception e) {
                        fatal("unable to compile task %s", task.getName());
                        debug(e.getMessage());
                    }
                    final long compileEnd = System.nanoTime();
                    if (graphContext.meta().shouldPrintCompileTimes()) {
                        if (PRINT_COMPILE_TIMES) {
                            System.out.printf("compile: " + task.getName() + "  " + +(compileEnd - compileStart) + "ns" + "\n");
                        }
                    }

                    if (graphContext.meta().isDebug()) {
                        debug("vm: compiled in %.9f s", (compileEnd - compileStart) * 1e-9);
                    }
                }

                if (isWarmup) {
                    popArgumentsFromStack(numArgs);
                    continue;
                }

                final TornadoInstalledCode installedCode = installedCodes[taskIndex];
                final Access[] accesses = task.getArgumentsAccess();

                if (redeployOnDevice || !stack.isOnDevice()) {
                    stack.reset();
                }
                for (int i = 0; i < numArgs; i++) {
                    final byte argType = buffer.get();
                    final int argIndex = buffer.getInt();

                    if (stack.isOnDevice()) {
                        continue;
                    }

                    if (argType == TornadoVMBytecodes.CONSTANT_ARG.index()) {
                        stack.push(constants.get(argIndex));
                    } else if (argType == TornadoVMBytecodes.REFERENCE_ARG.index()) {
                        final GlobalObjectState globalState = resolveGlobalObjectState(argIndex);
                        final DeviceObjectState objectState = globalState.getDeviceState(contexts.get(contextIndex));

                        TornadoInternalError.guarantee(objectState.isValid(), "object is not valid: %s %s", objects.get(argIndex), objectState);

                        stack.push(objects.get(argIndex), objectState);
                        if (accesses[i] == Access.WRITE || accesses[i] == Access.READ_WRITE) {
                            globalState.setOwner(device);
                            objectState.setContents(true);
                            objectState.setModified(true);
                        }
                    } else {
                        TornadoInternalError.shouldNotReachHere();
                    }
                }

                TaskMetaData metadata = null;
                if (task.meta() instanceof TaskMetaData) {
                    metadata = (TaskMetaData) task.meta();
                } else {
                    throw new RuntimeException("task.meta is not instanceof TaskMetada");
                }

                if (useDependencies) {
                    lastEvent = installedCode.launchWithDeps(stack, metadata, waitList);
                } else {
                    lastEvent = installedCode.launchWithoutDeps(stack, metadata);
                }
                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.ADD_DEP.index()) {
                final int eventList = buffer.getInt();
                if (isWarmup) {
                    continue;
                }
                if (useDependencies && lastEvent != -1) {
                    if (graphContext.meta().isDebug()) {
                        debug("vm: ADD_DEP %s to event list %d", lastEvent, eventList);
                    }
                    bytecodesList.append(String.format("ADD_DEP %s to event list %d\n", lastEvent, eventList));

                    TornadoInternalError.guarantee(eventsIndicies[eventList] < events[eventList].length, "event list is too small");
                    events[eventList][eventsIndicies[eventList]] = lastEvent;
                    eventsIndicies[eventList]++;
                }

            } else if (op == TornadoVMBytecodes.BARRIER.index()) {
                final int eventList = buffer.getInt();
                final int[] waitList = (useDependencies && eventList != -1) ? events[eventList] : null;

                if (isWarmup) {
                    continue;
                }

                if (graphContext.meta().isDebug()) {
                    debug("vm: BARRIER event list %d", eventList);
                }
                bytecodesList.append(String.format("BARRIER event list %d\n", eventList));

                if (contexts.size() == 1) {
                    final TornadoAcceleratorDevice device = contexts.get(0);
                    lastEvent = device.enqueueMarker(waitList);
                } else if (contexts.size() > 1) {
                    TornadoInternalError.shouldNotReachHere("unimplemented multi-context barrier");
                }

                if (eventList != -1) {
                    eventsIndicies[eventList] = 0;
                }
            } else if (op == TornadoVMBytecodes.END.index()) {
                if (graphContext.meta().isDebug()) {
                    debug("vm: END");
                }
                bytecodesList.append(String.format("END\n"));

                break;
            } else {
                if (graphContext.meta().isDebug()) {
                    debug("vm: invalid op 0x%x(%d)", op, op);
                }
                throw new TornadoException("[ERROR] TornadoVM Bytecode not recognized");
            }
        }

        Event barrier = EMPTY_EVENT;
        if (!isWarmup) {
            for (TornadoAcceleratorDevice dev : contexts) {
                if (useDependencies) {
                    final int event = dev.enqueueMarker();
                    barrier = dev.resolveEvent(event);
                }

                if (USE_VM_FLUSH) {
                    dev.flush();
                }
            }
        }

        final long t1 = System.nanoTime();
        final double elapsed = (t1 - t0) * 1e-9;
        if (!isWarmup) {
            totalTime += elapsed;
            invocations++;
        }

        if (graphContext.meta().isDebug()) {
            debug("vm: complete elapsed=%.9f s (%d iterations, %.9f s mean)", elapsed, invocations, (totalTime / invocations));
        }

        buffer.reset();

        if (TornadoOptions.printBytecodes) {
            System.out.println(bytecodesList.toString());
        }

        return barrier;
    }

    public void printTimes() {
        System.out.printf("vm: complete %d iterations - %.9f s mean and %.9f s total\n", invocations, (totalTime / invocations), totalTime);
    }

    public void clearProfiles() {
        for (final SchedulableTask task : tasks) {
            task.meta().getProfiles().clear();
        }
    }

    public void dumpEvents() {
        if (!ENABLE_PROFILING || !graphContext.meta().shouldDumpEvents()) {
            info("profiling and/or event dumping is not enabled");
            return;
        }

        for (final TornadoAcceleratorDevice device : contexts) {
            device.dumpEvents();
        }
    }

    public void dumpProfiles() {
        if (!graphContext.meta().shouldDumpProfiles()) {
            info("profiling is not enabled");
            return;
        }

        for (final SchedulableTask task : tasks) {
            final TaskMetaData meta = (TaskMetaData) task.meta();
            for (final TornadoEvents eventset : meta.getProfiles()) {
                final BitSet profiles = eventset.getProfiles();
                for (int i = profiles.nextSetBit(0); i != -1; i = profiles.nextSetBit(i + 1)) {

                    if (!(eventset.getDevice() instanceof TornadoAcceleratorDevice)) {
                        throw new RuntimeException("TornadoDevice not found");
                    }

                    TornadoAcceleratorDevice device = (TornadoAcceleratorDevice) eventset.getDevice();
                    final Event profile = device.resolveEvent(i);
                    if (profile.getStatus() == COMPLETE) {
                        System.out.printf("task: %s %s %.9f %9d %9d %9d\n", device.getDeviceName(), meta.getId(), profile.getExecutionTime(), profile.getSubmitTime(), profile.getStartTime(),
                                profile.getEndTime());
                    }
                }
            }
        }
    }

}
