# Shrinking is off in the release build for now, so nothing here is in use yet.
# These rules are ready for the day it is switched on.

# Media3 and Compose ship their own rules with the libraries. What follows is
# only for this app.

# The app talks to the other phone with org.json, which is part of the platform
# and is never shrunk. No rules are needed for it.

# Keep the entry point reachable from the launcher.
-keep class com.therealsoftware.duo.MainActivity { *; }

# The permission and data-safety declarations name these, so keep the names
# stable for anything that reads the merged manifest.
-keepnames class com.therealsoftware.duo.MediaServer
-keepnames class com.therealsoftware.duo.FrameServer
