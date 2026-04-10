# BudControl — Sony Earbuds Controller

A lightweight Android app for controlling Sony wireless earbuds (WF-1000XM4/XM5, WH-1000XM4/XM5, LinkBuds S, etc.) with a clean interface and home screen widgets.

## Features

- **ANC Control** — Toggle between Noise Canceling, Ambient Sound, and Off with a single tap
- **Ambient Sound Level** — Fine-tune transparency from 0 (minimal) to 20 (full passthrough)
- **Focus on Voice** — Highlight human voices in ambient sound mode
- **Wind Noise Reduction** — Toggle wind filtering in NC mode
- **Equalizer** — Quick-switch between presets (Bass Boost, Treble, Vocal, Bright, Mellow, etc.)
- **Speak to Chat** — Toggle the auto-pause-when-speaking feature
- **Battery Status** — Live Left / Right / Case battery levels with charging indicators
- **Home Screen Widgets**:
  - **1×1 ANC Toggle** — Tap to cycle NC → Ambient → Off
  - **2×2 Quick Controls** — ANC mode, battery, ambient level with +/− buttons
- **Persistent Notification** — Shows device name, current mode, battery; tap to cycle ANC
- **Auto-Reconnect** — Maintains connection and reconnects on dropout
- **Boot Auto-Connect** — Optionally reconnects to your last device on phone startup

## How It Works

The app communicates directly with your Sony earbuds over **Bluetooth RFCOMM** using the reverse-engineered Sony proprietary protocol (UUID `96CC203E-5068-46AD-B32D-E316F5E069BA`). No dependency on the Sony Sound Connect app — this replaces it entirely.

The protocol implementation is based on community reverse-engineering efforts and has been tested against the WF/WH-1000XM series. It should work with most Sony Bluetooth headphones/earbuds that use the same protocol.

## Requirements

- Android 8.0 (API 26) or higher
- Bluetooth-enabled device
- Sony earbuds/headphones already **paired** via Android Bluetooth settings
- Android Studio Hedgehog (2023.1.1) or newer to build

## Building

1. Clone or copy this project
2. Open in Android Studio
3. Let Gradle sync (it will download dependencies automatically)
4. Build → Run on your Android device (not emulator — needs real Bluetooth)

```bash
# Or build from command line:
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## First Launch

1. **Grant permissions** — The app will request Bluetooth and notification permissions
2. **Tap the Bluetooth icon** in the connection header to open the device picker
3. **Select your Sony earbuds** from the list of paired devices
4. The app connects via RFCOMM and loads your current settings

## Adding Widgets

1. Long-press your home screen → Widgets
2. Find "BudControl" in the widget list
3. Choose **ANC Toggle** (1×1) or **Quick Controls** (2×2)
4. Place on your home screen

## Supported Devices

Tested / expected to work:
- WF-1000XM4 / XM5
- WH-1000XM4 / XM5
- LinkBuds S
- WF-C700N

May work with other Sony Bluetooth audio devices that use the same RFCOMM protocol.

## Architecture

```
com.budcontrol.sony/
├── protocol/          Sony RFCOMM protocol (message framing, commands, parser)
├── bluetooth/         Bluetooth connection manager + device state model
├── service/           Foreground service for persistent connection
├── viewmodel/         ViewModel bridging service ↔ UI
├── widget/            Home screen widget providers
└── ui/                Jetpack Compose Material 3 interface
    ├── theme/         Dark theme with amber accents
    └── components/    ANC selector, ambient slider, battery, EQ, speak-to-chat
```

## Troubleshooting

- **"No paired Sony devices found"** — Pair your earbuds in Android Settings → Bluetooth first
- **Connection drops immediately** — Some models need the Sony app to complete initial setup once; after that this app takes over
- **Commands don't seem to work** — The exact protocol bytes can vary by firmware version. Check `SonyCommands.kt` and compare against your device's behavior
- **Widgets don't update** — Ensure the BudControl foreground service notification is visible (not killed by battery optimization)

## Credits

Protocol knowledge derived from open-source reverse-engineering projects:
- [SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient)
- [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30)

## License

MIT — do whatever you want with it.
