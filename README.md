# Duo

Two Android phones act as one screen. A fun little project.

![Two phones, one canvas](docs/icon.png)

## What this app does

Duo shows one canvas on two phones. Each phone shows one half of the canvas.
Move an object across the join. The object continues on the other phone.

This project takes its idea from foldable phones and dual-screen phones.
The Samsung Fold has one panel in one body. The Microsoft Surface Duo has two
panels in one body. Duo does the same job with two separate phones and a WiFi
link.

## What this app does not do

Duo cannot make two phones into one display at the system level. No application
can do this.

- A phone sends display data. A phone does not receive display data.
- A USB-C cable carries no video between two phones.
- The Samsung Fold uses one panel in one body. Two phones have two panels in
  two bodies.

Duo does the nearest useful thing. One app runs on both phones. Both phones
share one coordinate space. Each phone draws its own part of that space.

## Requirements

- Two Android phones with Android 13 or later.
- One WiFi network for both phones. One phone can supply a hotspot.
- A computer with JDK 17 or later and the Android SDK. You need the computer
  only to build the app.

## Build the app

```
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests:

```
./gradlew :app:testDebugUnitTest
```

## Install the app

Use ADB:

```
./gradlew :app:installDebug
```

MIUI on Xiaomi phones blocks this command. The message is
`INSTALL_FAILED_USER_RESTRICTED`. Install the APK by hand instead:

1. Copy the APK to the phone.
2. Open the Files app.
3. Touch the APK file.
4. Permit the installation from unknown sources.
5. Touch Install.

## Run the demo

1. Connect both phones to the same WiFi network. One phone can supply a
   hotspot.
2. Start Duo on both phones.
3. Touch HOST on the left phone. The screen shows the IP address.
4. Touch JOIN on the right phone.
5. Both phones show the canvas.
6. Drag the ball with your finger.

The ball obeys the walls of the full canvas. The join between the two phones is
not a wall.

Drag the ball to the edge of your phone. Lift your finger. The ball continues
onto the other phone.

Touch `back`, or use the back gesture, to go to the menu.

## Set the calibration

The two phones can report different values for their own pixel density. A shape
is then not the same physical size on both phones. The grid squares do not
agree.

Use the calibration control on the menu to correct this difference.

- Touch `+` to make the content on that phone larger.
- Touch `−` to make the content on that phone smaller.
- Touch `100%` to go back to the default value.

Do this before you touch HOST or JOIN. The app reads the calibration value when
the connection starts.

These values come from our two phones:

| Phone | Calibration |
|---|---|
| Google Pixel 9 Pro XL | 1.00x |
| Xiaomi Mi 11X | 0.89x |

The Pixel reports 160.8 dp for each inch of its panel. This value is almost
correct. The Mi 11X reports 143.5 dp for each inch. MIUI sets the density of the
Mi 11X to 440 dpi. That value makes the content too large.

## How it works

Both phones use one coordinate space. The unit of this space is the dp.

A dp is a density-independent pixel. Android supplies about 160 dp for each
inch. A shape of 100 dp is thus almost the same physical size on both phones.
This is the key idea of the project.

The host phone does these tasks:

- It keeps the state of the ball.
- It calculates the movement of the ball.
- It sends the position of the ball to the other phone at the frame rate.

The client phone does these tasks:

- It sends each touch to the host phone.
- It draws the ball at the position from the host phone.

The host sends one message to the client for each frame. The delay on a local
network is a few milliseconds. The user does not see this delay.

The two phones find each other in this sequence:

1. The client sends a UDP probe to the broadcast address.
2. The host answers the probe.
3. The client opens a TCP connection to the host.
4. The client sends its screen size to the host.
5. The host sends the full layout to the client.

### The files

```
app/src/main/java/com/azhar/duo/
  World.kt         The canvas and the movement of the ball. No Android imports.
  Link.kt          The discovery and the network connection.
  Session.kt       The connection between the network and the canvas.
  MainActivity.kt  The user interface and the touch input.
app/src/test/java/com/azhar/duo/
  WorldTest.kt     Eight tests for the join, the walls, and the release.
```

`World.kt` has no Android imports. You can thus test the geometry and the
movement on a computer.

### The messages

| Direction | Message |
|---|---|
| client to host | `{"t":"hello","w":<width>,"h":<height>}` |
| host to client | `{"t":"layout","aw":..,"ah":..,"bw":..,"bh":..}` |
| host to client | `{"t":"ball","x":..,"y":..,"vx":..,"vy":..}` for each frame |
| client to host | `{"t":"touch","x":..,"y":..,"d":true or false}` |

Both phones calculate the same layout from the same four numbers. The layout
message is thus only a sync point.

## How we made it

We started with the geometry. The first question was this: "How do two phones
agree on the size of one object?"

The answer was the dp. Android does the necessary work already. A shape in dp
has almost the same physical size on both phones. We did not need special
calibration code for the usual case.

Then we wrote `World.kt`. This file holds the canvas and the ball. We kept
Android out of this file. The file thus runs on a computer. We wrote eight unit
tests for the file before we touched the user interface.

The tests cover these conditions:

- The ball continues across the join. It does not stop there.
- The ball stops at the outer walls of the full canvas.
- A fast drag does not move the ball at a speed that is too high.

Then we wrote the network code. We used one TCP connection with JSON messages.
We did not use WebRTC. A local network needs no more than a socket.

Then we wrote the user interface.

### Problems we found

Problem: The first version started one coroutine for each message. Coroutines
do not keep their sequence. The ball could jump back to an old position.

Correction: One writer coroutine now reads from a queue.

Problem: A closed socket sends a "disconnect" event from its own thread. This
event can arrive after the app goes back to the menu.

Correction: The app now ignores events from an old connection.

Problem: The connection of the Mi 11X failed at first. The phone was behind a
USB hub. The ADB tool does not always find a device behind a hub.

Correction: Connect the phone directly to the computer.

Problem: The reported density of the Mi 11X is not correct for its panel. The
content was 11 percent too large on that phone.

Correction: We added the calibration control to the menu.

## Ideas for more work

- Show a video on both phones. Each phone shows one half of the picture. The
  geometry code does not change.
- Let the app find the correct calibration automatically. One method: show a
  shape of a known physical size and let the user measure it.
- Connect more than two phones. `World.kt` already divides the canvas into any
  number of parts. `Link.kt` is the part that assumes two phones.

## Limits

- The app needs a WiFi network. A USB-C cable does not carry the signal.
- Both phones must be on the same subnet for the automatic discovery. The host
  screen shows the IP address of the host.
- The app does not change the system. It cannot show other apps across the two
  screens.

## License

MIT. See `LICENSE`.
