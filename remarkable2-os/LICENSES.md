# Licenses

- **CipherCodex OS (this directory)**: same license as the parent CipherCodex repository.
- **Qt 6** (device runtime + SDK): LGPL-3.0; linked dynamically against the libraries shipped on
  the device / in the official SDK. No Qt sources modified.
- **epaper Qt platform plugin** (`libepaper.so`, ships with reMarkable OS): LGPL-2.1 per
  `/usr/share/common-licenses/epaper-qpa/` on device. Loaded at runtime as a Qt plugin; not
  copied or linked into this codebase.
- **Xochitl** is proprietary; nothing from it is copied, linked, or reverse-engineered. Our shell
  only start/stops its systemd service.
- Planned (later phases, recorded when added): MuPDF (AGPL-3.0) for PDF rendering; SQLite
  (public domain).
