package se.bitcraze.crazyflie.lib.crtp;

import java.nio.ByteBuffer;

/** TinyDrone high-level landing command, matching COMMAND_LAND_2 in the controller. */
public class LandPacket extends CrtpPacket {
    private static final byte COMMAND_LAND_2 = 8;

    public LandPacket() {
        super(0, CrtpPort.HIGH_LEVEL_COMMANDER);
    }

    @Override
    protected void serializeData(ByteBuffer buffer) {
        buffer.put(COMMAND_LAND_2);
        buffer.put((byte) 0); // groupMask: all drones
        buffer.putFloat(0.0f); // target height
        buffer.putFloat(0.0f); // ignored when useCurrentYaw is true
        buffer.put((byte) 1); // useCurrentYaw
        buffer.putFloat(3.0f); // duration, matching TinyDrone Controller
    }

    @Override
    protected int getDataByteCount() {
        return 1 + 1 + 4 + 4 + 1 + 4;
    }
}
