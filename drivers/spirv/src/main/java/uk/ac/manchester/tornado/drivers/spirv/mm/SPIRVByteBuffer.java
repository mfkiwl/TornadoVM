package uk.ac.manchester.tornado.drivers.spirv.mm;

import uk.ac.manchester.tornado.drivers.spirv.SPIRVDeviceContext;
import uk.ac.manchester.tornado.runtime.common.RuntimeUtilities;

import java.nio.ByteBuffer;

// FIXME <Refactor> <S>
public class SPIRVByteBuffer {

    protected ByteBuffer buffer;
    private long numBytes;
    private long offset;
    private SPIRVDeviceContext deviceContext;

    public SPIRVByteBuffer(long numBytes, long offset, SPIRVDeviceContext deviceContext) {
        this.numBytes = numBytes;
        this.offset = offset;
        this.deviceContext = deviceContext;

        buffer = ByteBuffer.allocate((int) numBytes);
        buffer.order(deviceContext.getDevice().getByteOrder());
    }

    public long getSize() {
        return this.numBytes;
    }

    public void read() {
        read(null);
    }

    private void read(int[] events) {
        // deviceContext.readBuffer(heapPointer() + offset, numBytes, buffer.array(), 0,
        // events);
    }

    private long heapPointer() {
        return deviceContext.getMemoryManager().toBuffer();
    }

    public int getInt(int offset) {
        return buffer.getInt(offset);
    }

    protected long toAbsoluteAddress() {
        return deviceContext.getMemoryManager().toAbsoluteDeviceAddress(offset);
    }

    public void write() {
        write(null);
    }

    public void write(int[] events) {
        // deviceContext.writeBuffer(heapPointer() + offset, numBytes, buffer.array(),
        // 0, events);
    }

    // FIXME <PENDING> enqueueWrite

    public ByteBuffer buffer() {
        return buffer;
    }

    // FIXME <REFACTOR> This method is common with the 3 backends
    public void dump(int width) {
        buffer.position(buffer.capacity());
        System.out.printf("Buffer  : capacity = %s, in use = %s, device = %s \n", RuntimeUtilities.humanReadableByteCount(numBytes, true),
                RuntimeUtilities.humanReadableByteCount(buffer.position(), true), deviceContext.getDevice().getDeviceName());
        for (int i = 0; i < buffer.position(); i += width) {
            System.out.printf("[0x%04x]: ", i + toAbsoluteAddress());
            for (int j = 0; j < Math.min(buffer.capacity() - i, width); j++) {
                if (j % 2 == 0) {
                    System.out.print(" ");
                }
                if (j < buffer.position() - i) {
                    System.out.printf("%02x", buffer.get(i + j));
                } else {
                    System.out.print("..");
                }
            }
            System.out.println();
        }
    }

    public SPIRVByteBuffer getSubBuffer(int offset, int numBytes) {
        return new SPIRVByteBuffer(numBytes, offset, deviceContext);
    }

}
