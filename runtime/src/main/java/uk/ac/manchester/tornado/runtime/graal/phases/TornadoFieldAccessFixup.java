package uk.ac.manchester.tornado.runtime.graal.phases;

import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.java.AccessFieldNode;
import org.graalvm.compiler.nodes.java.AccessIndexedNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.phases.BasePhase;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.runtime.graal.nodes.calc.TornadoAddressArithmeticNode;

import java.util.ArrayDeque;

public class TornadoFieldAccessFixup extends BasePhase<TornadoHighTierContext> {

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        ArrayDeque<LoadFieldNode> worklist = new ArrayDeque<>();
        graph.getNodes().filter(ParameterNode.class).forEach(parameterNode -> {
            worklist.addAll(parameterNode.usages().filter(LoadFieldNode.class).snapshot());
            parameterNode.usages()
                    .filter(usage -> usage instanceof PiNode && ((PiNode) usage).object() instanceof ParameterNode)
                    .forEach(usage ->
                            worklist.addAll(usage.usages().filter(LoadFieldNode.class).snapshot())
                    );
        });

        while (!worklist.isEmpty()) {
            LoadFieldNode loadField = worklist.poll();
            worklist.addAll(loadField.usages().filter(LoadFieldNode.class).snapshot());

            loadField.usages().forEach(usage -> {
                if (usage instanceof AccessIndexedNode) {
                    AccessIndexedNode loadStoreIndexed = (AccessIndexedNode) usage;
                    ValueNode base = loadField.object();
                    if (base instanceof PiNode) {
                        base = ((PiNode) base).object();
                    } else if (base instanceof TornadoAddressArithmeticNode) {
                        base = ((TornadoAddressArithmeticNode) base).getBase();
                    }
                    TornadoAddressArithmeticNode addNode = new TornadoAddressArithmeticNode(base, loadField);
                    graph.addWithoutUnique(addNode);
                    loadStoreIndexed.setArray(addNode);
                } else if (usage instanceof AccessFieldNode) {
                    AccessFieldNode accessFieldNode = (AccessFieldNode) usage;
                    ValueNode base = loadField.object();
                    if (base instanceof PiNode) {
                        base = ((PiNode) base).object();
                    } else if (base instanceof TornadoAddressArithmeticNode) {
                        base = ((TornadoAddressArithmeticNode) base).getBase();
                    }
                    TornadoAddressArithmeticNode addNode = new TornadoAddressArithmeticNode(base, loadField);
                    graph.addWithoutUnique(addNode);
                    if (accessFieldNode instanceof LoadFieldNode) {
                        ((LoadFieldNode) accessFieldNode).setObject(addNode);
                    } else if (accessFieldNode instanceof StoreFieldNode) {
                        StoreFieldNode oldStoreNode = (StoreFieldNode) accessFieldNode;
                        StoreFieldNode storeFieldNode = new StoreFieldNode(addNode, oldStoreNode.field(), oldStoreNode.value());
                        graph.addWithoutUnique(storeFieldNode);
                        graph.replaceFixedWithFixed(oldStoreNode, storeFieldNode);
                    } else {
                        TornadoInternalError.shouldNotReachHere("Unexpected node type = %s", accessFieldNode.getClass().getName());
                    }
                }
            });
        }
    }
}
