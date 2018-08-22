package uk.ac.manchester.tornado.api.runtinface;

import uk.ac.manchester.tornado.api.common.GenericDevice;

public interface TornadoRuntimeInterface {

    public TornadoRuntimeInterface callRuntime();

    public void clearObjectState();

    public TornadoGenericDriver getDriver(int index);

    public <D extends TornadoGenericDriver> D getDriver(Class<D> type);

    public int getNumDrivers();

    public GenericDevice getDefaultDevice();

}
