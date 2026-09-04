package se.bitcraze.crazyflie.lib.crtp;

import java.nio.ByteBuffer;

/** Crazyflie high-level relative takeoff command (COMMAND_TAKEOFF_WITH_VELOCITY). */
public class TakeoffPacket extends CrtpPacket {
    private static final byte COMMAND_TAKEOFF_WITH_VELOCITY = 9;

    private final float mRelativeHeight;
    private final float mVelocity;

    public TakeoffPacket(float relativeHeight, float velocity) {
        super(0, CrtpPort.HIGH_LEVEL_COMMANDER);
        mRelativeHeight = relativeHeight;
        mVelocity = velocity;
    }

    @Override
    protected void serializeData(ByteBuffer buffer) {
        buffer.put(COMMAND_TAKEOFF_WITH_VELOCITY);
        buffer.put((byte) 0); // groupMask: all drones
        buffer.putFloat(mRelativeHeight);
        buffer.put((byte) 1); // heightIsRelative
        buffer.putFloat(0.0f); // ignored when useCurrentYaw is true
        buffer.put((byte) 1); // useCurrentYaw
        buffer.putFloat(mVelocity);
    }

    @Override
    protected int getDataByteCount() {
        return 1 + 1 + 4 + 1 + 4 + 1 + 4;
    }
}
