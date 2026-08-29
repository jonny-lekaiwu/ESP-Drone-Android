# TinyDrone Android

TinyDrone is an Android controller for the TinyDrone aircraft. It sends flight-control commands over UDP, receives the aircraft's UDP JPEG video stream, displays telemetry such as battery voltage, and supports yaw-lock and headless flight modes.

The Android application ID is `com.tinydrone.android`. GitHub Actions produces a versioned `TinyDrone-vX.Y.Z.apk` debug package for test installation.

## Open-source origins and license

TinyDrone is derived from Espressif's ESP-Drone Android application, which itself is based on the Bitcraze Crazyflie Android client. The original copyright notices and project history are retained. This project remains licensed under the GNU General Public License v2; see [LICENSE.txt](LICENSE.txt).

Upstream references:

 - [Espressif ESP-Drone Android](https://github.com/EspressifApps/ESP-Drone-Android)
 - [Bitcraze Crazyflie Android client](https://github.com/bitcraze/crazyflie-android-client)

## Contributions

Please check the local [CONTRIBUTING.md](CONTRIBUTING.md). Upstream contribution history remains available in the repositories linked above.
