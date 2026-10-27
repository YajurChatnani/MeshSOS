# OFFGRID SOS — Direct Offline Emergency Mesh

**Offgrid SOS** is a native Android emergency communication application designed for reliable peer-to-peer messaging between nearby devices in zero-connectivity environments (earthquakes, floods, network outages, remote wilderness).

The application operates without internet, cellular data, or centralized servers. It provides two manually selectable direct transports: **Wi-Fi Aware (NAN)** and **Bluetooth Classic (RFCOMM)**, underpinned by a unified, versioned SOS protocol with length-prefixed stream framing, local persistence, ACK confirmation, and bounded backoff retries.

---

## 1. Key Features

- **No Server or Internet Required**: Operates completely device-to-device.
- **Dual Transports**:
  - **Wi-Fi Aware (NAN)**: High-speed, ad-hoc discovery and local TCP socket data transfer.
  - **Bluetooth Classic (RFCOMM)**: Ubiquitous compatibility with streaming RFCOMM sockets.
- **Unified SOS Protocol**:
  - Globally unique UUID message IDs.
  - UTF-8 JSON payload with 4-byte big-endian length prefix.
  - Direction tracking (`INCOMING`, `OUTGOING`).
  - Strict delivery lifecycle (`PENDING` → `SENT` → `ACKNOWLEDGED` / `FAILED` / `EXPIRED`).
  - True application-level ACKs (confirmed only when receiver validates and stores the alert).
  - Duplicate suppression and bounded exponential backoff retries.
- **Non-blocking GPS Coordinates**:
  - 3.5-second timeout on coordinate acquisition.
  - Sends immediate SOS even if GPS is denied or acquiring fix fails.
  - Never fabricates coordinates.
- **Screen-Off Background Operation**:
  - Dedicated foreground service (`connectedDevice` foreground service type).
  - Persistent ongoing notification with quick "Disconnect" action.
  - Decoupled `StateFlow` architecture preserving socket state across activity destruction.
- **Future Mesh Compatibility**:
  - Hop count tracking (initialized to 0 for initial 2-device scope).
  - Expiry and store-and-forward queue metadata in local Room database.
- **Emergency Utility UI**:
  - Material 3 design system with dark/light theme support.
  - Focused, calm typography and soft neutral backgrounds.
  - Distinctive deep red `#DC2626` emergency trigger with confirmation sheet to prevent accidental sends.

---

## 2. Architecture Overview

The codebase is structured into clean architectural layers:

```
app/src/main/java/com/example/meshsos/
├── data/
│   ├── local/
│   │   ├── SosDatabase.kt            # Room Database definition
│   │   ├── SosMessageDao.kt          # Flow queries & atomic status transitions
│   │   └── SosMessageEntity.kt       # Room message entity with indexed timestamps
│   ├── preferences/
│   │   └── DevicePreferences.kt      # Persistent device UUID & transport choice
│   └── repository/
│       └── SosRepository.kt          # Delivery tracking, ACK matching, bounded retry backoff
├── protocol/
│   ├── SosProtocol.kt                # SosMessage, MessageType, DeliveryStatus, validation
│   └── MessageFraming.kt             # 4-byte big-endian length prefix framing & FrameBufferDecoder
├── transport/
│   ├── Transport.kt                  # Common Transport interface
│   ├── TransportType.kt              # WIFI_AWARE, BLUETOOTH_CLASSIC
│   ├── ConnectionState.kt            # Sealed state machine
│   ├── DiscoveredPeer.kt             # Peer model
│   ├── TransportManager.kt           # Singleton coordinator for active transport
│   ├── bluetooth/
│   │   └── BluetoothClassicTransport.kt # RFCOMM server socket listener and client connection
│   └── wifi/
│       └── WifiAwareTransport.kt     # Wi-Fi Aware publisher/subscriber, specifier, & TCP socket
├── service/
│   ├── ForegroundConnectionService.kt# Connected-device foreground service for screen-off persistence
│   └── ServiceNotificationHelper.kt  # Notification channel and builder
├── location/
│   └── SosLocationManager.kt         # Non-blocking location acquisition with 3.5s timeout
├── permissions/
│   └── PermissionManager.kt          # Version-aware checks (Android 12, 13, 14, 16)
├── ui/
│   ├── theme/                        # Material 3 colors, typography, and theme
│   ├── components/                   # DeviceCard, SosBigButton, TransportSelector, StatusBadge
│   ├── screens/                      # HomeScreen, ConnectionScreen, SosConfirmDialog, HistoryScreen, SettingsScreen
│   └── viewmodel/                    # MainViewModel
├── MainActivity.kt                   # Edge-to-edge Compose activity & permission requests
└── MeshSosApplication.kt             # Application singleton container
```

---

## 3. Protocol & Framing Specification

Because TCP and RFCOMM are stream-oriented byte streams without message boundaries, all payloads are framed with a length header:

### Wire Format
```
+-----------------------------------+-----------------------------------+
|  4-Byte Big-Endian Payload Length |      UTF-8 JSON Payload Bytes     |
|             (Int32)               |           (Variable)              |
+-----------------------------------+-----------------------------------+
```
- **Max Payload Size**: 16 KiB (16,384 bytes). Frames claiming larger lengths are rejected immediately as protocol violations.
- **Encoding/Decoding**: `MessageFraming.encodeFrame()` / `MessageFraming.readFrame()` and `FrameBufferDecoder` (handles chunk fragmentation and multiple frames per read).

### JSON Payload Schema
```json
{
  "v": 1,
  "id": "e4b6d370-4f51-4045-8bc7-5f72439a31a9",
  "sender": "d82ab730-24e1-4c4f-9e63-a64119d690a2",
  "type": "SOS",
  "ts": 1729668000000,
  "exp": 1729754400000,
  "text": "Medical Emergency: Trapped on 2nd floor",
  "lat": 12.9716,
  "lon": 77.5946,
  "acc": 12.5,
  "hops": 0,
  "ackFor": null
}
```

### ACK Schema
```json
{
  "v": 1,
  "id": "78a9c21b-419b-449e-b8d2-7e9b010c7e23",
  "sender": "a1b2c3d4-e5f6-7890-1234-56789abcdef0",
  "type": "ACK",
  "ts": 1729668005000,
  "exp": 1729754405000,
  "text": "ACK",
  "lat": null,
  "lon": null,
  "acc": null,
  "hops": 0,
  "ackFor": "e4b6d370-4f51-4045-8bc7-5f72439a31a9"
}
```

---

## 4. Build Instructions

### Prerequisites
- JDK 17 (or compatible JDK 21).
- Android SDK installed (`minSdk = 26`, `targetSdk = 37`, `compileSdk = 37`).
- Android Gradle Plugin 9.4.1 / Gradle 9.6.0.

### Compile and Run Unit Tests
```bash
./gradlew test
```

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Output APK Location
The compiled debug APK is located at:
```
app/build/outputs/apk/debug/app-debug.apk
```
File size: ~21.6 MB.

---

## 5. Physical Device Testing Guide

### Test Matrix
- **Device 1**: Samsung Galaxy A35 5G (Android 16, One UI 8.5)
- **Device 2**: Realme 9 Pro+ (Android 14)

### Step 1: Install APK on Both Phones
```bash
adb -s <DEVICE_1_SERIAL> install app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_2_SERIAL> install app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Permissions Check
Launch the app on both phones and accept runtime permission dialogs:
- **Nearby Devices / Wi-Fi**: Required for Wi-Fi Aware.
- **Bluetooth Scan / Connect**: Required for Bluetooth Classic.
- **Notifications**: Required for Android 13+ background service notification.
- **Location**: Optional on-demand (used for emergency coordinates).

Check **Settings & Diagnostics** screen on both phones to verify all capabilities show green **Ready** badges.

### Step 3: Test Bluetooth Classic
1. On both phones, select **Bluetooth** on the transport selector.
2. Tap the Connection Card or navigation icon to open the **Connection** screen.
3. Tap **Find Nearby Peers**.
4. Both phones will advertise RFCOMM and scan.
5. Once Phone 2 appears in Phone 1's list, tap **Connect**.
6. Verify status turns green: **Connected to <Device Name>**.
7. Return to Home screen and tap **SOS**.
8. In the confirmation dialog, review the emergency text and coordinates, then tap **SEND EMERGENCY SOS**.
9. **Verify Receiver**: Phone 2 immediately receives the SOS alert and saves it to its local Room database.
10. **Verify Sender**: Phone 1 receives the ACK from Phone 2 and updates the status from `SENT` to `ACKNOWLEDGED`.

### Step 4: Test Wi-Fi Aware (NAN)
1. On both phones, select **Wi-Fi Aware** on the transport selector.
2. Ensure Wi-Fi is enabled on both phones (no access point or router connection required).
3. Open **Connection** screen and tap **Find Nearby Peers**.
4. Both phones publish and subscribe to `Offline_SOS_Mesh`.
5. Tap **Connect** on the discovered peer.
6. Verify the Wi-Fi Aware network specifier establishes the local TCP socket.
7. Send an SOS and verify delivery and ACK.

### Step 5: Screen-Off Background Verification
1. Establish a connection between both devices (Wi-Fi Aware or Bluetooth).
2. Verify the persistent notification **Offgrid SOS Active** is visible in the status bar.
3. Turn off screens on both phones (lock screens).
4. Send an SOS from Phone 1 or wake Phone 1 to trigger SOS while Phone 2 remains screen-off.
5. Verify that Phone 2 maintains socket connectivity and validates and saves the alert while its screen is off.
