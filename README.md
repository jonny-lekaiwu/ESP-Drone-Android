# TinyDrone Android

TinyDrone is an Android controller for the TinyDrone aircraft. It sends flight-control commands over UDP, receives the aircraft's UDP JPEG video stream, displays telemetry such as battery voltage, and supports yaw-lock and headless flight modes.

The Android application ID is `com.tinydrone.android`. GitHub Actions produces a versioned `TinyDrone-vX.Y.Z.apk` debug package for test installation.

## Remote ID station data

While connected to the TinyDrone Wi-Fi controller, the app requests the phone's WGS-84 location and sends a station update once per second through CRTP port `0x0D`, channel `0x02`. The 24-byte little-endian payload contains longitude and latitude multiplied by `1e7`, WGS-84 altitude in centimetres, and a 48-bit Unix millisecond timestamp. The existing UDP transport adds the CRTP header and modulo-256 checksum before sending to `192.168.43.42:2390`.

| Payload bytes | Value |
| --- | --- |
| 0..3 | `52 01 flags 00`; flag bits 0/1/2 mean position/altitude/time valid |
| 4..7 | WGS-84 longitude × `1e7`, signed int32 LE |
| 8..11 | WGS-84 latitude × `1e7`, signed int32 LE |
| 12..15 | WGS-84 altitude in centimetres, signed int32 LE |
| 16..21 | Unix milliseconds, unsigned 48-bit LE |
| 22..23 | GB 46750 time-accuracy level and reserved zero |

If a fresh phone position is unavailable, the app continues sending time but clears the position and altitude validity flags. Location updates stop when the UDP controller disconnects.

## Open-source origins and license

TinyDrone is derived from Espressif's ESP-Drone Android application, which itself is based on the Bitcraze Crazyflie Android client. The original copyright notices and project history are retained. This project remains licensed under the GNU General Public License v2; see [LICENSE.txt](LICENSE.txt).

Upstream references:

 - [Espressif ESP-Drone Android](https://github.com/EspressifApps/ESP-Drone-Android)
 - [Bitcraze Crazyflie Android client](https://github.com/bitcraze/crazyflie-android-client)

## Contributions

Please check the local [CONTRIBUTING.md](CONTRIBUTING.md). Upstream contribution history remains available in the repositories linked above.
