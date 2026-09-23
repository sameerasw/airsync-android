# Third-Party Notices

AirSync is licensed under the Mozilla Public License 2.0 (see [LICENSE](LICENSE)).
Portions of AirSync are derived from third-party projects under their own licenses, listed below.

## ClipSync

- Project: https://github.com/WinShell-Bhanu/Clipsync
- License: MIT
- Used in:
  - `app/src/main/java/com/sameerasw/airsync/service/ClipboardAccessibilityService.kt`
  - `app/src/main/java/com/sameerasw/airsync/presentation/ui/activities/ClipboardGhostActivity.kt`

The background clipboard capture approach (detecting copy actions through an accessibility
service and reading the clipboard from a transient invisible activity) is adapted from ClipSync.

```
MIT License

Copyright (c) 2026 Bhanu

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
