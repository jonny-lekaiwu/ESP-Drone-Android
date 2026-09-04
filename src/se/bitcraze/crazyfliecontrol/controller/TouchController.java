/**
 *    ||          ____  _ __
 * +------+      / __ )(_) /_______________ _____  ___
 * | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *  ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Copyright (C) 2013 Bitcraze AB
 *
 * Crazyflie Nano Quadcopter Client
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package se.bitcraze.crazyfliecontrol.controller;

import se.bitcraze.crazyfliecontrol2.MainActivity;

import com.MobileAnarchy.Android.Widgets.Joystick.JoystickMovedListener;
import com.MobileAnarchy.Android.Widgets.Joystick.JoystickView;

/**
 * The TouchController uses the on-screen joysticks to control the roll, pitch, yaw and thrust values.
 * The mapping of the axes can be changed with the "mode" setting in the preferences.
 *
 * For example, mode 3 (default) maps roll to the left X-Axis, pitch to the left Y-Axis,
 * yaw to the right X-Axis and thrust to the right Y-Axis.
 *
 */
public class TouchController extends AbstractController {

    public static final int ALT_HOLD_STATE_LOCKED = 0;
    public static final int ALT_HOLD_STATE_DESCENDING = 1;
    public static final int ALT_HOLD_STATE_HOLDING = 2;
    public static final int ALT_HOLD_STATE_ASCENDING = 3;

    private static final float ALT_HOLD_UNLOCK_INPUT = 37768.0f / 65535.0f;
    private static final int ALT_HOLD_DESCEND_MAX = 27766;
    private static final int ALT_HOLD_CENTER = 32767;
    private static final int ALT_HOLD_ASCEND_MIN = 37768;

    protected int mMovementRange = 1000;  // "resolution"

    protected JoystickView mJoystickViewLeft;
    protected JoystickView mJoystickViewRight;
    private volatile boolean mAltitudeHoldControl;
    private volatile boolean mAltitudeHoldUnlocked;

    public TouchController(Controls controls, MainActivity activity, JoystickView joystickviewLeft, JoystickView joystickviewRight) {
        super(controls, activity);
        this.mJoystickViewLeft = joystickviewLeft;
        this.mJoystickViewRight = joystickviewRight;
        this.mJoystickViewLeft.setMovementRange(mMovementRange);
        this.mJoystickViewRight.setMovementRange(mMovementRange);
        updateAutoReturnMode();
    }

    private void updateAutoReturnMode() {
        if (mAltitudeHoldControl) {
            // Match TinyDrone Controller: the altitude-hold throttle stays
            // exactly where the pilot releases it.
            setThrustAutoReturnMode(JoystickView.AUTO_RETURN_NONE, false);
            return;
        }
        this.mJoystickViewLeft.setAutoReturnMode(isLeftAnalogFullTravelThrust() ? JoystickView.AUTO_RETURN_BOTTOM : JoystickView.AUTO_RETURN_CENTER);
        this.mJoystickViewLeft.autoReturn(true);
        this.mJoystickViewRight.setAutoReturnMode(isRightAnalogFullTravelThrust() ? JoystickView.AUTO_RETURN_BOTTOM : JoystickView.AUTO_RETURN_CENTER);
        this.mJoystickViewRight.autoReturn(true);
    }

    private void setThrustAutoReturnMode(int mode, boolean moveNow) {
        JoystickView thrustJoystick = isThrustRightAnalog() ? mJoystickViewRight : mJoystickViewLeft;
        thrustJoystick.setAutoReturnMode(mode);
        if (moveNow) thrustJoystick.autoReturn(true);
    }

    public void setAltitudeHoldControl(boolean enabled) {
        mAltitudeHoldControl = enabled;
        mAltitudeHoldUnlocked = false;
        updateAutoReturnMode();
    }

    public float getAltitudeHoldThrustAbsolute() {
        if (!mAltitudeHoldControl || !mAltitudeHoldUnlocked) return 0.0f;
        float input = isThrustRightAnalog() ? mControls.getRightAnalog_Y() : mControls.getLeftAnalog_Y();
        float deadzone = mControls.getDeadzone();
        if (input < -deadzone) {
            float scale = (input + 1.0f) / (1.0f - deadzone);
            return Math.max(0.0f, Math.min(1.0f, scale)) * ALT_HOLD_DESCEND_MAX;
        }
        if (input > deadzone) {
            float scale = (input - deadzone) / (1.0f - deadzone);
            return ALT_HOLD_ASCEND_MIN
                    + Math.max(0.0f, Math.min(1.0f, scale)) * (65535 - ALT_HOLD_ASCEND_MIN);
        }
        return ALT_HOLD_CENTER;
    }

    public boolean isAltitudeHoldCentered() {
        if (!mAltitudeHoldControl || !mAltitudeHoldUnlocked) return false;
        float input = isThrustRightAnalog() ? mControls.getRightAnalog_Y() : mControls.getLeftAnalog_Y();
        return Math.abs(input) <= mControls.getDeadzone();
    }

    public int getAltitudeHoldDisplayState() {
        if (!mAltitudeHoldUnlocked) return ALT_HOLD_STATE_LOCKED;
        float input = isThrustRightAnalog() ? mControls.getRightAnalog_Y() : mControls.getLeftAnalog_Y();
        if (input < -mControls.getDeadzone()) return ALT_HOLD_STATE_DESCENDING;
        if (input > mControls.getDeadzone()) return ALT_HOLD_STATE_ASCENDING;
        return ALT_HOLD_STATE_HOLDING;
    }

    private float updateAltitudeHoldThrustInput(float tilt, boolean thrustAxis) {
        if (!thrustAxis || !mAltitudeHoldControl) return tilt;
        if (!mAltitudeHoldUnlocked) {
            float fullTravelInput = (tilt + 1.0f) / 2.0f;
            if (fullTravelInput <= ALT_HOLD_UNLOCK_INPUT) return fullTravelInput;
            mAltitudeHoldUnlocked = true;
            setThrustAutoReturnMode(JoystickView.AUTO_RETURN_NONE, false);
        }
        return tilt;
    }

    @Override
    public void enable() {
        super.enable();
        this.mJoystickViewLeft.setOnJoystickMovedListener(_listenerLeft);
        this.mJoystickViewRight.setOnJoystickMovedListener(_listenerRight);
        updateAutoReturnMode();
    }

    @Override
    public void disable() {
        mControls.setRightAnalogY(0);
        mControls.setRightAnalogX(0);
        mControls.setLeftAnalogY(0);
        mControls.setLeftAnalogX(0);
        this.mJoystickViewLeft.setOnJoystickMovedListener(null);
        this.mJoystickViewRight.setOnJoystickMovedListener(null);
        super.disable();
    }

    public String getControllerName() {
        return "touch controller";
    }

    private JoystickMovedListener _listenerRight = new JoystickMovedListener() {

        @Override
        public void OnMoved(float pan, float tilt) {
            boolean thrustAxis = isThrustRightAnalog();
            if (mAltitudeHoldControl) {
                tilt = updateAltitudeHoldThrustInput(tilt, thrustAxis);
            } else if (isRightAnalogFullTravelThrust()) {
                tilt = (tilt + 1.0f) / 2.0f;
            }
            mControls.setRightAnalogY(tilt);

            mControls.setRightAnalogX(pan);

            updateFlightData();
        }

        @Override
        public void OnReleased() {
            // Log.i("Joystick-Right", "Release");
            mControls.setRightAnalogY(0);
            mControls.setRightAnalogX(0);
        }

        public void OnReturnedToCenter() {
            // Log.i("Joystick-Right", "Center");
            mControls.setRightAnalogY(0);
            mControls.setRightAnalogX(0);
        }
    };

    private JoystickMovedListener _listenerLeft = new JoystickMovedListener() {

        @Override
        public void OnMoved(float pan, float tilt) {
            boolean thrustAxis = !isThrustRightAnalog();
            if (mAltitudeHoldControl) {
                tilt = updateAltitudeHoldThrustInput(tilt, thrustAxis);
            } else if (isLeftAnalogFullTravelThrust()) {
                tilt = (tilt + 1.0f) / 2.0f;
            }
            mControls.setLeftAnalogY(tilt);

            mControls.setLeftAnalogX(pan);

            updateFlightData();
        }

        @Override
        public void OnReleased() {
            mControls.setLeftAnalogY(0);
            mControls.setLeftAnalogX(0);
        }

        public void OnReturnedToCenter() {
            mControls.setLeftAnalogY(0);
            mControls.setLeftAnalogX(0);
        }
    };


    public boolean isThrustRightAnalog() {
        return (mControls.getMode() == 1 || mControls.getMode() == 3);
    }

    public boolean isLeftAnalogFullTravelThrust() {
        return mControls.isTouchThrustFullTravel() && !isThrustRightAnalog();
    }

    public boolean isRightAnalogFullTravelThrust() {
        return mControls.isTouchThrustFullTravel() && isThrustRightAnalog();
    }

}
