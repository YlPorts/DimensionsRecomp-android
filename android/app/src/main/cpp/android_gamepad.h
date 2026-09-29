#pragma once

namespace dimensions::android {

// Creates one SDL3 virtual Xbox-style gamepad. Safe to call repeatedly.
bool EnsureTouchGamepad();

// Releases the virtual device during a clean shutdown.
void ShutdownTouchGamepad();

}  // namespace dimensions::android
