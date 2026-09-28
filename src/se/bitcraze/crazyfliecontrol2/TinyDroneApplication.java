package se.bitcraze.crazyfliecontrol2;

import android.app.Application;

public class TinyDroneApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TinyDroneLog.initialize(this);
    }
}
