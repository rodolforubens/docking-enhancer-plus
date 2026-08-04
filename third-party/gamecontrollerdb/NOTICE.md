# SDL_GameControllerDB

Bundled at `android/data/src/main/assets/gamecontrollerdb.txt`, filtered to the `platform:Linux` and
`platform:Android` entries of the community database at
https://github.com/mdqinc/SDL_GameControllerDB — the daemon reads the pad's Linux event stream, so
those are the entries that describe what it sees.

Licensed under the zlib license (see [LICENSE.txt](LICENSE.txt)), copyright Sam Lantinga and the
database's community of contributors.

## What it is used for

A first-time default. When a controller the database knows is seen and the user has never mapped it,
its entry is translated into this app's binding table so the pad works before the editor is ever
opened. The editor then edits over that default like over any other mapping.

## The index translation

Database values (`b3`, `a2`, `h0.4`) are SDL joystick indices, not evdev codes. SDL's Linux backend
assigns them by walking the device's declared capabilities in code order — buttons from
`BTN_JOYSTICK` (0x120) up and then from 0, axes in `ABS_*` order skipping the hat range, hats as
their own pair list. `SdlJoystickIndex.kt` reproduces that walk over the pad's `/proc` capability
bitmasks; refreshing the bundled file is just re-running the filter, no re-translation involved.
