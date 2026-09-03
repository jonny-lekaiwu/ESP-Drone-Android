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

package se.bitcraze.crazyfliecontrol2;

import java.math.BigDecimal;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;

import com.tinydrone.android.R;
import se.bitcraze.crazyfliecontrol.controller.TouchController;

/**
 * Compound component that groups together flight data UI elements
 *
 */
public class FlightDataView extends LinearLayout {

    private static final String LOG_TAG = "FlightDataView";

    private TextView mTextView_pitch;
    private TextView mTextView_roll;
    private TextView mTextView_thrust;
    private TextView mTextView_yaw;
    private TextView mTextView_fps;
    private TextView mTextView_operationState;
    private TextView mTextView_altitudeHold;

    public FlightDataView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setOrientation(LinearLayout.HORIZONTAL);

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.view_flight_data, this, true);

        mTextView_pitch = (TextView) findViewById(R.id.pitch);
        mTextView_roll = (TextView) findViewById(R.id.roll);
        mTextView_thrust = (TextView) findViewById(R.id.thrust);
        mTextView_yaw = (TextView) findViewById(R.id.yaw);
        mTextView_fps = (TextView) findViewById(R.id.video_fps);
        mTextView_operationState = (TextView) findViewById(R.id.rid_operation_status);
        mTextView_altitudeHold = (TextView) findViewById(R.id.altitude_hold_status);
        //initialize
        mTextView_pitch.setText(format(R.string.pitch, 0.0));
        mTextView_roll.setText(format(R.string.roll, 0.0));
        mTextView_thrust.setText(format(R.string.thrust, 0.0));
        mTextView_yaw.setText(format(R.string.yaw, 0.0));
        updateVideoFps(0.0f);
        setRidOperationState(0x00);
        setAltitudeHoldState(-1);
    }

    public FlightDataView(Context context) {
      this(context, null);
    }

    public void updateFlightData(float pitch, float roll, float thrust, float yaw) {
        mTextView_pitch.setText(format(R.string.pitch, round(pitch)));
        mTextView_roll.setText(format(R.string.roll, round(roll)));
        mTextView_thrust.setText(format(R.string.thrust, round(thrust)));
        mTextView_yaw.setText(format(R.string.yaw, round(yaw)));
    }

    public void updateVideoFps(float fps) {
        mTextView_fps.setText(format(R.string.video_fps, fps));
    }

    public void setAltitudeHoldState(int state) {
        int text;
        switch (state) {
            case TouchController.ALT_HOLD_STATE_LOCKED:
                text = R.string.altitude_hold_locked;
                break;
            case TouchController.ALT_HOLD_STATE_DESCENDING:
                text = R.string.altitude_hold_descending;
                break;
            case TouchController.ALT_HOLD_STATE_HOLDING:
                text = R.string.altitude_hold_holding;
                break;
            case TouchController.ALT_HOLD_STATE_ASCENDING:
                text = R.string.altitude_hold_ascending;
                break;
            default:
                mTextView_altitudeHold.setVisibility(View.GONE);
                return;
        }
        mTextView_altitudeHold.setText(text);
        mTextView_altitudeHold.setVisibility(View.VISIBLE);
    }

    public void setRidOperationState(int state) {
        String value;
        switch (state) {
            case 0x00: value = getResources().getString(R.string.rid_state_not_reported); break;
            case 0x01: value = getResources().getString(R.string.rid_state_ground); break;
            case 0x02: value = getResources().getString(R.string.rid_state_airborne); break;
            case 0x03: value = getResources().getString(R.string.rid_state_emergency); break;
            case 0x04: value = getResources().getString(R.string.rid_state_failure_normal); break;
            case 0x05: value = getResources().getString(R.string.rid_state_failure_emergency); break;
            default:
                value = state >= 0x06 && state <= 0x0f
                        ? getResources().getString(R.string.rid_state_reserved)
                        : getResources().getString(R.string.rid_state_unknown);
                break;
        }
        mTextView_operationState.setText(
                getResources().getString(R.string.rid_operation_status, value));
    }

    private String format(int identifier, Object o){
        return String.format(getResources().getString(identifier), o);
    }

    public static double round(double unrounded) {
        try {
            BigDecimal bd = new BigDecimal(unrounded);
            BigDecimal rounded = bd.setScale(2, BigDecimal.ROUND_HALF_UP);
            return rounded.doubleValue();
        } catch (NumberFormatException nfe) {
            Log.e(LOG_TAG, "unrounded: " + unrounded + ", NumberFormatException: " + nfe);
            return Double.NaN;
        }
    }

}
