package uk.ac.manchester.tornado.drivers.spirv.graal.compiler;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.code.CompilationResult;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;

public class SPIRVCompilationResult extends CompilationResult {

    private Set<ResolvedJavaMethod> nonInlinedMethods;
    private TaskMetaData taskMetaData;

    public SPIRVCompilationResult(String methodName, TaskMetaData taskMetaData) {
        super(methodName);
        this.taskMetaData = taskMetaData;
    }

    public Set<ResolvedJavaMethod> getNonInlinedMethods() {
        return nonInlinedMethods;
    }

    public void setNonInlinedMethods(Set<ResolvedJavaMethod> value) {
        nonInlinedMethods = value;
    }

    public static byte[] prependToTargetCode(byte[] targetCode, byte[] codeToPrepend) {
        final int size = targetCode.length + codeToPrepend.length + 1;

        final byte[] newCode = new byte[size];
        Arrays.fill(newCode, (byte) 0);

        final ByteBuffer buffer = ByteBuffer.wrap(newCode);
        buffer.put(codeToPrepend);
        buffer.put((byte) '\n');
        buffer.put(targetCode);
        return newCode;
    }

    public TaskMetaData getTaskMetaData() {
        return this.taskMetaData;
    }

}
