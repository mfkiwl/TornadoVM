/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
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
package uk.ac.manchester.tornado.runtime.sketcher;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.phases.PhaseSuite;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.phases.util.Providers;
import uk.ac.manchester.tornado.runtime.graal.compiler.TornadoSketchTier;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

public class SketchRequest {

    final int driverIndex;
    final int deviceIndex;
    final TaskMetaData meta;
    final ResolvedJavaMethod resolvedMethod;
    final Providers providers;
    final PhaseSuite<HighTierContext> graphBuilderSuite;
    final TornadoSketchTier sketchTier;

    public SketchRequest(TaskMetaData meta, ResolvedJavaMethod resolvedMethod, Providers providers, PhaseSuite<HighTierContext> graphBuilderSuite, TornadoSketchTier sketchTier, int driverIndex, int deviceIndex) {
        this.resolvedMethod = resolvedMethod;
        this.providers = providers;
        this.graphBuilderSuite = graphBuilderSuite;
        this.sketchTier = sketchTier;
        this.meta = meta;
        this.driverIndex = driverIndex;
        this.deviceIndex = deviceIndex;
    }

    public void run() {
        TornadoSketcher.buildSketch(this);
    }
}
