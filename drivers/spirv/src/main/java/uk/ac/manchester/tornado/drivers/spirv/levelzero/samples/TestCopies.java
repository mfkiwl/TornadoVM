package uk.ac.manchester.tornado.drivers.spirv.levelzero.samples;

import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroByteBuffer;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroCommandList;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroCommandQueue;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroContext;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroDevice;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.LevelZeroDriver;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandListDescription;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandListHandle;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueueDescription;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueueGroupProperties;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueueGroupPropertyFlags;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueueHandle;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueueMode;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeCommandQueuePriority;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeContextDesc;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeDeviceMemAllocDesc;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeDeviceProperties;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeDevicesHandle;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeDriverHandle;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.ZeInitFlag;
import uk.ac.manchester.tornado.drivers.spirv.levelzero.Ze_Structure_Type;

import java.util.Arrays;

public class TestCopies {

    public static LevelZeroContext zeInitContext(LevelZeroDriver driver) {
        if (driver == null) {
            return null;
        }

        int result = driver.zeInit(ZeInitFlag.ZE_INIT_FLAG_GPU_ONLY);
        LevelZeroUtils.errorLog("zeInit", result);

        int[] numDrivers = new int[1];
        result = driver.zeDriverGet(numDrivers, null);
        LevelZeroUtils.errorLog("zeDriverGet", result);

        ZeDriverHandle driverHandler = new ZeDriverHandle(numDrivers[0]);
        result = driver.zeDriverGet(numDrivers, driverHandler);
        LevelZeroUtils.errorLog("zeDriverGet", result);

        ZeContextDesc contextDescription = new ZeContextDesc();
        contextDescription.setSType(Ze_Structure_Type.ZE_STRUCTURE_TYPE_CONTEXT_DESC);
        LevelZeroContext context = new LevelZeroContext(driverHandler, contextDescription);
        result = context.zeContextCreate(driverHandler.getZe_driver_handle_t_ptr()[0], 0);
        LevelZeroUtils.errorLog("zeContextCreate", result);
        return context;
    }

    public static LevelZeroDevice zeGetDevices(LevelZeroContext context, LevelZeroDriver driver) {

        ZeDriverHandle driverHandler = context.getDriver();

        // Get number of devices in a driver
        int[] deviceCount = new int[1];
        int result = driver.zeDeviceGet(driverHandler, 0, deviceCount, null);
        LevelZeroUtils.errorLog("zeDeviceGet", result);

        // Instantiate a device Handler
        ZeDevicesHandle deviceHandler = new ZeDevicesHandle(deviceCount[0]);
        result = driver.zeDeviceGet(driverHandler, 0, deviceCount, deviceHandler);
        LevelZeroUtils.errorLog("zeDeviceGet", result);

        // ============================================
        // Get the device
        // ============================================
        LevelZeroDevice device = driver.getDevice(driverHandler, 0);
        ZeDeviceProperties deviceProperties = new ZeDeviceProperties();
        result = device.zeDeviceGetProperties(device.getDeviceHandlerPtr(), deviceProperties);
        LevelZeroUtils.errorLog("zeDeviceGetProperties", result);
        return device;
    }

    public static int getCommandQueueOrdinal(LevelZeroDevice device) {
        int[] numQueueGroups = new int[1];
        int result = device.zeDeviceGetCommandQueueGroupProperties(device.getDeviceHandlerPtr(), numQueueGroups, null);
        LevelZeroUtils.errorLog("zeDeviceGetCommandQueueGroupProperties", result);

        if (numQueueGroups[0] == 0) {
            throw new RuntimeException("Number of Queue Groups is 0 for device: " + device.getDeviceProperties().getName());
        }
        int ordinal = numQueueGroups[0];

        ZeCommandQueueGroupProperties[] commandQueueGroupProperties = new ZeCommandQueueGroupProperties[numQueueGroups[0]];
        result = device.zeDeviceGetCommandQueueGroupProperties(device.getDeviceHandlerPtr(), numQueueGroups, commandQueueGroupProperties);
        LevelZeroUtils.errorLog("zeDeviceGetCommandQueueGroupProperties", result);

        for (int i = 0; i < numQueueGroups[0]; i++) {
            if ((commandQueueGroupProperties[i].getFlags()
                    & ZeCommandQueueGroupPropertyFlags.ZE_COMMAND_QUEUE_GROUP_PROPERTY_FLAG_COMPUTE) == ZeCommandQueueGroupPropertyFlags.ZE_COMMAND_QUEUE_GROUP_PROPERTY_FLAG_COMPUTE) {
                ordinal = i;
                break;
            }
        }
        return ordinal;
    }

    public static ZeCommandQueueHandle createCommandQueue(LevelZeroContext context, LevelZeroDevice device) {
        // Create Command Queue
        ZeCommandQueueDescription cmdDescriptor = new ZeCommandQueueDescription();
        cmdDescriptor.setFlags(0);
        cmdDescriptor.setMode(ZeCommandQueueMode.ZE_COMMAND_QUEUE_MODE_DEFAULT);
        cmdDescriptor.setPriority(ZeCommandQueuePriority.ZE_COMMAND_QUEUE_PRIORITY_NORMAL);
        cmdDescriptor.setOrdinal(getCommandQueueOrdinal(device));
        cmdDescriptor.setIndex(0);

        ZeCommandQueueHandle commandQueue = new ZeCommandQueueHandle();
        int result = context.zeCommandQueueCreate(context.getContextHandle().getContextPtr()[0], device.getDeviceHandlerPtr(), cmdDescriptor, commandQueue);
        LevelZeroUtils.errorLog("zeCommandQueueCreate", result);

        return commandQueue;
    }

    public static ZeCommandListHandle createCommandList(LevelZeroContext context, LevelZeroDevice device) {
        ZeCommandListDescription cmdListDescriptor = new ZeCommandListDescription();
        cmdListDescriptor.setFlags(0);
        cmdListDescriptor.setCommandQueueGroupOrdinal(getCommandQueueOrdinal(device));
        ZeCommandListHandle commandList = new ZeCommandListHandle();
        int result = context.zeCommandListCreate(context.getContextHandle().getContextPtr()[0], device.getDeviceHandlerPtr(), cmdListDescriptor, commandList);
        LevelZeroUtils.errorLog("zeCommandListCreate", result);
        return commandList;
    }

    public static boolean testAppendMemoryCopyFromHeapToDeviceToHeap(LevelZeroContext context, LevelZeroDevice device) {

        final int allocSize = 4096;
        byte[] heapBuffer = new byte[allocSize];

        LevelZeroByteBuffer deviceBuffer = new LevelZeroByteBuffer();
        byte[] heapBuffer2 = new byte[allocSize];

        ZeCommandQueueHandle zeCommandQueueHandle = createCommandQueue(context, device);
        ZeCommandListHandle zeCommandQueueListHandle = createCommandList(context, device);
        LevelZeroCommandList commandList = new LevelZeroCommandList(context, zeCommandQueueListHandle);
        LevelZeroCommandQueue commandQueue = new LevelZeroCommandQueue(context, zeCommandQueueHandle);

        ZeDeviceMemAllocDesc deviceMemAllocDesc = new ZeDeviceMemAllocDesc();
        deviceMemAllocDesc.setOrdinal(0);
        deviceMemAllocDesc.setFlags(0);
        int alignment = 1;

        // This is the equivalent of a clCreateBuffer
        int result = context.zeMemAllocDevice(context.getContextHandle().getContextPtr()[0], deviceMemAllocDesc, allocSize, alignment, device.getDeviceHandlerPtr(), deviceBuffer);
        LevelZeroUtils.errorLog("zeMemAllocDevice", result);

        // Initialize second buffer (Java side) to 0
        Arrays.fill(heapBuffer2, (byte) 0);

        // Fill heap buffer (Java side)
        for (int i = 0; i < allocSize; i++) {
            heapBuffer[i] = 'a';
        }

        // Copy from HEAP -> Device Allocated Memory
        result = commandList.zeCommandListAppendMemoryCopy(commandList.getCommandListHandlerPtr(), deviceBuffer, heapBuffer, allocSize, null, 0, null);
        LevelZeroUtils.errorLog("zeCommandListAppendMemoryCopy", result);
        result = commandList.zeCommandListAppendBarrier(commandList.getCommandListHandlerPtr(), null, 0, null);
        LevelZeroUtils.errorLog("zeCommandListAppendBarrier", result);

        // Copy From Device-Allocated memory to host (heapBuffer2)
        result = commandList.zeCommandListAppendMemoryCopy(commandList.getCommandListHandlerPtr(), heapBuffer2, deviceBuffer, allocSize, null, 0, null);
        LevelZeroUtils.errorLog("zeCommandListAppendMemoryCopy", result);

        // Close the command list
        result = commandList.zeCommandListClose(commandList.getCommandListHandlerPtr());
        LevelZeroUtils.errorLog("zeCommandListClose", result);
        result = commandQueue.zeCommandQueueExecuteCommandLists(commandQueue.getCommandQueueHandlerPtr(), 1, commandList.getCommandListHandler(), null);
        LevelZeroUtils.errorLog("zeCommandQueueExecuteCommandLists", result);
        result = commandQueue.zeCommandQueueSynchronize(commandQueue.getCommandQueueHandlerPtr(), Long.MAX_VALUE);
        LevelZeroUtils.errorLog("zeCommandQueueSynchronize", result);

        boolean isValid = true;
        for (int i = 0; i < allocSize; i++) {
            if (heapBuffer[i] != heapBuffer2[i]) {
                System.out.println(heapBuffer[i] + " != " + heapBuffer2[i]);
                isValid = false;
                break;
            }
        }

        // Free resources
        context.zeMemFree(context.getDefaultContextPtr(), deviceBuffer);
        context.zeCommandListDestroy(commandList.getCommandListHandler());
        context.zeCommandQueueDestroy(commandQueue.getCommandQueueHandle());
        return isValid;
    }

    public static void main(String[] args) {

        LevelZeroDriver driver = new LevelZeroDriver();
        LevelZeroContext context = zeInitContext(driver);
        LevelZeroDevice device = zeGetDevices(context, driver);

        ZeDeviceProperties deviceProperties = device.getDeviceProperties();
        System.out.println("Device: ");
        System.out.println("\tName     : " + deviceProperties.getName());
        System.out.println("\tVendor ID: " + Integer.toHexString(deviceProperties.getVendorId()));

        boolean isValid = testAppendMemoryCopyFromHeapToDeviceToHeap(context, device);
        System.out.println("is valid? " + isValid);

    }
}
