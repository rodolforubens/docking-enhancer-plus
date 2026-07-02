# Notices and attributions

Docking Enhancer (Odin Input Mirror)
Copyright (C) 2026 Ezequiel-CE

This program is free software, distributed under the terms of the GNU General
Public License v2.0. See [LICENSE](LICENSE) for the full text.

## Third-party techniques and code

### No-root PServer technique

The no-root approach used by this project — obtaining the stock firmware's
`PServerBinder` service via reflection and running privileged commands through
it — was pioneered by **ClusterTune** and popularized by **PULSE**
(https://github.com/keiretrogaming/pulse), which is licensed under the GNU
General Public License v2.0.

The PServerBinder access code in
`android/data/src/main/java/com/odininputmirror/data/PServerExec.kt` is adapted
from PULSE's `RootExec.kt`. This project is licensed under the GPL v2.0 in part
because it incorporates that work.

Please keep this attribution intact in any fork or redistribution; the GPL
requires it.
