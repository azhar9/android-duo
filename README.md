# Duo

Two Android phones act as one screen. A fun little project.

![Two phones, one canvas](docs/icon.png)

## What this app does

Duo puts one picture across two phones. Each phone shows one half of it, and the
two halves line up as if the screens were one.

The app has three modes. The host picks the mode. Both phones then follow it.

| Mode | What the two phones show |
|---|---|
| Grid | A grid and a ball. Drag the ball from one phone to the other. This is the setup screen: use it to set the gap and check the alignment. |
| Video | One video file. Either phone can serve it; the other streams it. |
| Web | One web page. The host lays it out once and streams the picture. |

This project takes its idea from foldable phones and dual-screen phones. The
Samsung Fold has one panel in one body. The Microsoft Surface Duo has two panels
in one body. Duo does the same job with two separate phones and a WiFi link.

## What this app does not do

Duo cannot make two phones into one display at the system level. No application
can do this.

- A phone sends display data. A phone does not receive display data.
- A USB-C cable carries no video between two phones.
- The Samsung Fold uses one panel in one body. Two phones have two panels in two
  bodies.

Duo does the nearest useful thing. One app runs on both phones. Both phones
share one coordinate space. Each phone draws its own part of that space.

## Requirements

- Two Android phones with Android 8.0 or later. The two we used run
  Android 13 and Android 17, and nothing below 13 has been tried.
- One WiFi network for both phones. One phone can supply a hotspot.
- A computer with JDK 17 or later and the Android SDK. You need the computer
  only to build and install the app.

A USB cable gives the two phones no network between them. Use the cable only to
install the app.

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

MIUI on Xiaomi phones can block this command. The message is
`INSTALL_FAILED_USER_RESTRICTED`. Do these steps:

1. Open Settings.
2. Open Additional settings, then Developer options.
3. Turn on **Install via USB**.
4. Run the install command again.

If the command still fails, install the APK by hand:

1. Copy the APK to the phone.
2. Open the Files app.
3. Touch the APK file.
4. Permit the installation from unknown sources.
5. Touch Install.

## Choose a clip

Touch **Choose** on either phone and pick a video from that phone's gallery.

Whichever phone you pick on serves the clip, and the other one streams it. There
is no rule to remember about which phone is the host — pick on the phone that
holds the video you want, and the other follows.

You can change it while the app runs. Touch **Swap** on either phone and that
phone takes over.

The serving phone does not copy the file. It runs a small HTTP server and the
other phone's player asks for the parts it needs, so playback starts at once and
seeking works. A long film does not have to cross the network before it starts.

**Sound comes from the phone that holds the clip.** Two phones in one room
playing the same audio a few milliseconds apart would echo, so the other one is
muted.

## Run the demo

1. Connect both phones to the same WiFi network. One phone can supply a hotspot.
2. Start Duo on both phones.
3. Touch HOST on the left phone. The screen shows the IP address.
4. Touch JOIN on the right phone.
5. Both phones show the canvas.
6. Turn the gap dial until the picture agrees across the join.
7. Drag the ball with your finger.

The ball obeys the walls of the full canvas. The join between the two phones is
not a wall.

Drag the ball to the edge of your phone. Lift your finger. The ball continues
onto the other phone.

Touch `back`, or use the back gesture, to go to the menu.

## Choose the mode

Touch **Grid**, **Video**, or **Web** on the setup screen before you start. The
host sends the choice to the client.

**Grid.** A grid of 50 dp squares and one ball. This is the setup screen: the
grid shows the join clearly, so use it to set the gap and check the alignment.

**Video.** Both phones play the same clip. The host owns the clock. It sends its
position four times each second. The client takes the play state from the host,
and moves to the host position if it drifts more than 200 ms.

Only the host makes sound. Two phones in one room would echo.

**Web.** The host types an address. That phone lays the page out once, shows
its own half, and streams the other half as pictures.

Only one phone runs a browser, so the two halves cannot disagree about layout.
This is also why a video on the page plays with sound: the host is an ordinary
browser on an ordinary device, and its sound simply plays.

The picture travels as a JPEG for each frame, about 10 each second. On a local
network that is roughly 1 MB for each second. Text is slightly soft because it
is a picture of text.

**Known limitation.** The page may not fill the whole canvas width. Android's
WebView chooses its own zoom in ways that are hard to predict, so the right-hand
phone can show empty space where the page should continue. The two halves always
agree with each other, but the page may not reach the far edge.


## How the phones sit

The two phones can sit **side by side** or **stacked**, one above the other. Set
it on the setup screen; the host tells the client.

A wide video suits two phones side by side. A tall video suits two stacked
phones, where the picture fills the height instead of being lost to the sides.

The app is not locked to portrait. Turn either phone and it follows.

## Calibrate the two phones

Do this on the menu before you touch HOST or JOIN. The app reads both values
when the connection starts.

### Size

The two phones can report different values for their own pixel density. A shape
is then not the same physical size on both phones. The grid squares do not agree.

- Touch `+` to make the content on that phone larger.
- Touch `−` to make the content on that phone smaller.
- Touch `100%` to go back to the default value.

These values come from our two phones:

| Phone | Size |
|---|---|
| Google Pixel 9 Pro XL | 1.00x |
| Xiaomi Mi 11X | 0.90x |

The Pixel reports 160.8 dp for each inch of its panel. This value is almost
correct. The Mi 11X reports 143.5 dp for each inch. MIUI sets the density of the
Mi 11X to 440 dpi. That value makes the content 11 percent too large.

### Panel gap

The two screens do not touch. A real gap sits between them. The bezels hide that
band of the canvas. Correct the gap, or the picture jumps at the join.

1. Put the two phones side by side.
2. Measure the distance between the two lit screens with a ruler.
3. Touch `−` or `+` until the number agrees with your measurement.

You can change the gap while the app runs. A small dial at the top of the canvas
does the same job. The host sends each change to the client at once.

The centre circle helps you. The app draws it half on one screen and half on the
other. Turn the dial until the two halves make one complete circle.

## How it works

### One coordinate space

Both phones use one coordinate space. The unit of this space is the dp.

A dp is a density-independent pixel. Android supplies about 160 dp for each
inch. A shape of 100 dp is thus almost the same physical size on both phones.
This is the key idea of the project.

### The span

The canvas runs along one axis. Side by side, that axis is horizontal. Stacked,
it is vertical. Everything else follows from that choice: the slice, the gap,
the walls the ball bounces off, and the crop taken for the other phone.

### The gap between the panels

The left phone holds the logical range `[0, aw)`. The gap holds `[aw, aw + gap)`.
The right phone holds `[aw + gap, aw + gap + bw)`.

No phone draws the middle range. The bezels hide it. The ball crosses it without
help. Only the two outer edges are walls.

### The video

The video always fills the full logical width. That choice makes the picture
continuous across the join. Each phone then shows its own window onto the video.

The app scales the video on each phone by the same amount, and moves it by the
width of that phone's slice. The test proves that the hidden band equals the
physical gap.

### The web picture

`FrameServer` takes one ServerSocket for the whole session and holds it open.
Binding again between viewers would leave a window where a reconnect is refused.

Each frame is a four-byte length and then the picture. A socket delivers bytes
in arbitrary pieces, so without a length the reader cannot tell a whole frame
from half of one.

The host draws the page into one bitmap and cuts both halves out of it, so the
two halves always agree. It reuses that bitmap for every frame — making a
four-megapixel bitmap ten times a second would bury the collector.

### The host is the clock

The host is authoritative. It owns the ball, runs the physics, holds the
playback clock, and picks the mode. The client sends its raw touches and draws
whatever the host says.

One message goes from the host to the client for each frame. The delay on a
local network is a few milliseconds. The user does not see this delay.

### The files

```
app/src/main/java/com/azhar/duo/
  World.kt         The canvas, the axis, the gap, the crop maths, and the ball.
  Link.kt          The discovery and the network connection.
  MediaServer.kt   Serves the picked clip to the other phone over HTTP.
  Range.kt         Reads HTTP Range headers. Pure, so it is testable.
  Stream.kt        Sends the rendered page to the other phone as frames.
  Session.kt       The connection between the network, the media, and the canvas.
  MainActivity.kt  The user interface, the video surface, and the web surfaces.
app/src/test/java/com/azhar/duo/
  WorldTest.kt     Thirty tests for the geometry and the physics.
  RangeTest.kt     Thirteen tests for byte ranges.
  StreamTest.kt    Ten tests for the frame protocol.
```

`World.kt` has no Android imports. You can thus test the geometry and the
movement on a computer.

### The messages

The two phones trade JSON messages on one TCP socket.

| Direction | Message |
|---|---|---|
| client to host | `{"t":"hello","w":<width>,"h":<height>,"n":"<clip name>"}` |
| host to client | `{"t":"layout","aw":..,"ah":..,"bw":..,"bh":..,"m":"<mode>","url":"..","gap":..,"ax":"<axis>","src":"<who holds the clip>","n":".."}` |
| host to client | `{"t":"ball","x":..,"y":..,"vx":..,"vy":..}` for each frame |
| host to client | `{"t":"vid","p":<position ms>,"r":<playing>}` |
| client to host | `{"t":"touch","x":..,"y":..,"d":<down>}` |
| either to either | `{"t":"gap","mm":<gap>}` |
| either to either | `{"t":"axis","a":"<Horizontal or Vertical>"}` |
| either to either | `{"t":"source","n":"<clip name>"}` |
| either to either | `{"t":"scroll","f":<fraction>}` |

The clip itself does not travel on this socket. It goes over HTTP, on its own
port, so the player can ask for ranges.

Both phones calculate the same layout from the same four numbers. The layout
message is thus only a sync point.

## How we made it

We started with the geometry. The first question was this: "How do two phones
agree on the size of one object?"

The answer was the dp. Android does the necessary work already. A shape in dp
has almost the same physical size on both phones. We did not need special
calibration code for the usual case.

Then we wrote `World.kt`. This file holds the canvas and the ball. We kept
Android out of this file. The file thus runs on a computer. We wrote the unit
tests before we touched the user interface.

The tests cover these conditions:

- The ball continues across the join. It does not stop there.
- The ball stops at the outer walls of the full canvas.
- A fast drag does not move the ball at a speed that is too high.
- The band behind the bezels equals the gap.

Then we wrote the network code. We used one TCP connection with JSON messages.
We did not use WebRTC. A local network needs no more than a socket.

Then we wrote the user interface, the video renderer, and the web renderer.

### Problems we found

**Problem.** The first version started one coroutine for each message.
Coroutines do not keep their sequence. The ball could jump back to an old
position.

**Correction.** One writer coroutine now reads from a queue.

**Problem.** A closed socket sends a "disconnect" event from its own thread.
This event can arrive after the app goes back to the menu.

**Correction.** The app now ignores events from an old connection.

**Problem.** The ADB tool did not find the Mi 11X. The phone was behind a USB
hub. The phone was correct, and the cable was correct.

**Correction.** Connect the phone directly to the computer.

**Problem.** The reported density of the Mi 11X is not correct for its panel.
The content was 11 percent too large on that phone.

**Correction.** We added the size control to the menu.

**Problem.** The video drew at the wrong size. It filled the whole screen
instead of one half of the picture. The app calculated the crop, but never
applied it.

**Correction.** A surface reports its size only after the layout pass. Nothing
else on that screen caused a redraw, so the crop maths never received a size.
The app now takes the size from a layout callback. The web surface had the same
fault, and the same correction.

## Limits

- The app needs a WiFi network. A USB-C cable does not carry the signal.
- Both phones must be on the same subnet for the automatic discovery. The host
  screen shows the IP address of the host.
- The app does not change the system. It cannot show other apps across the two
  screens.
- In web mode the page may not reach the far edge of the canvas, because WebView
  chooses its own zoom.
- The picture in web mode is a JPEG for each frame, so fine text is softer than
  real text.
- The two phones must sit at the same height. The app does not correct a
  vertical offset.

## Ideas for more work

- Let the app find the correct size value by itself. One method: show a shape of
  a known physical size and let the user measure it.
- Correct the vertical alignment. The two phones can sit at different heights.
- Connect more than two phones. `World.kt` already divides the canvas into any
  number of parts. `Link.kt` is the part that assumes two phones.
- Encode the web picture as H.264 instead of JPEG. That would cut the bandwidth
  several times over and make the text sharper.
- Show a different source on each phone, and move an object between them.

## License

MIT. See `LICENSE`.
