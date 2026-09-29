#include "android_gamepad.h"

#include <SDL3/SDL.h>

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>

namespace dimensions::android {
namespace {

std::mutex g_gamepad_mutex;
SDL_JoystickID g_virtual_id = 0;
SDL_Joystick* g_virtual_joystick = nullptr;

constexpr int kButtonCount = 14;

constexpr SDL_GamepadButton kButtonMap[kButtonCount] = {
    SDL_GAMEPAD_BUTTON_SOUTH,           // A
    SDL_GAMEPAD_BUTTON_EAST,            // B
    SDL_GAMEPAD_BUTTON_WEST,            // X
    SDL_GAMEPAD_BUTTON_NORTH,           // Y
    SDL_GAMEPAD_BUTTON_LEFT_SHOULDER,   // LB
    SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER,  // RB
    SDL_GAMEPAD_BUTTON_BACK,
    SDL_GAMEPAD_BUTTON_START,
    SDL_GAMEPAD_BUTTON_LEFT_STICK,
    SDL_GAMEPAD_BUTTON_RIGHT_STICK,
    SDL_GAMEPAD_BUTTON_DPAD_UP,
    SDL_GAMEPAD_BUTTON_DPAD_DOWN,
    SDL_GAMEPAD_BUTTON_DPAD_LEFT,
    SDL_GAMEPAD_BUTTON_DPAD_RIGHT,
};

Sint16 StickToAxis(float value) {
  const float clamped = std::clamp(value, -1.0f, 1.0f);
  if (clamped <= -1.0f) {
    return SDL_JOYSTICK_AXIS_MIN;
  }
  return static_cast<Sint16>(
      std::lround(clamped * static_cast<float>(SDL_JOYSTICK_AXIS_MAX)));
}

Sint16 TriggerToAxis(float value) {
  const float clamped = std::clamp(value, 0.0f, 1.0f);
  return static_cast<Sint16>(
      std::lround(clamped * static_cast<float>(SDL_JOYSTICK_AXIS_MAX)));
}

bool EnsureTouchGamepadLocked() {
  if (g_virtual_joystick) {
    return true;
  }

  SDL_VirtualJoystickDesc desc{};
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.vendor_id = 0x045E;
  desc.product_id = 0x028E;
  desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
  desc.nbuttons = SDL_GAMEPAD_BUTTON_COUNT;
  desc.axis_mask = (Uint32(1) << SDL_GAMEPAD_AXIS_COUNT) - 1;
  desc.button_mask = (Uint32(1) << SDL_GAMEPAD_BUTTON_COUNT) - 1;
  desc.name = "Dimensions Touch Controller";

  g_virtual_id = SDL_AttachVirtualJoystick(&desc);
  if (!g_virtual_id) {
    SDL_LogError(SDL_LOG_CATEGORY_INPUT,
                 "Dimensions touch controller: attach failed: %s",
                 SDL_GetError());
    return false;
  }

  g_virtual_joystick = SDL_OpenJoystick(g_virtual_id);
  if (!g_virtual_joystick) {
    SDL_LogError(SDL_LOG_CATEGORY_INPUT,
                 "Dimensions touch controller: open failed: %s",
                 SDL_GetError());
    SDL_DetachVirtualJoystick(g_virtual_id);
    g_virtual_id = 0;
    return false;
  }

  SDL_SetJoystickPlayerIndex(g_virtual_joystick, 0);
  SDL_Log("Dimensions touch controller attached as SDL joystick %u",
          static_cast<unsigned>(g_virtual_id));
  return true;
}

void SetStickLocked(int stick, float x, float y) {
  if (!g_virtual_joystick) {
    return;
  }

  const int axis_x =
      stick == 0 ? SDL_GAMEPAD_AXIS_LEFTX : SDL_GAMEPAD_AXIS_RIGHTX;
  const int axis_y =
      stick == 0 ? SDL_GAMEPAD_AXIS_LEFTY : SDL_GAMEPAD_AXIS_RIGHTY;

  SDL_SetJoystickVirtualAxis(g_virtual_joystick, axis_x, StickToAxis(x));
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, axis_y, StickToAxis(y));
}

void SetTriggerLocked(int side, float value) {
  if (!g_virtual_joystick) {
    return;
  }
  const int axis = side == 0 ? SDL_GAMEPAD_AXIS_LEFT_TRIGGER
                             : SDL_GAMEPAD_AXIS_RIGHT_TRIGGER;
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, axis, TriggerToAxis(value));
}

void SetButtonLocked(int button, bool down) {
  if (!EnsureTouchGamepadLocked() || button < 0 || button >= kButtonCount) {
    return;
  }
  SDL_SetJoystickVirtualButton(g_virtual_joystick,
                               static_cast<int>(kButtonMap[button]), down);
}

}  // namespace

bool EnsureTouchGamepad() {
  std::lock_guard<std::mutex> lock(g_gamepad_mutex);
  return EnsureTouchGamepadLocked();
}

void ShutdownTouchGamepad() {
  std::lock_guard<std::mutex> lock(g_gamepad_mutex);
  if (g_virtual_joystick) {
    SDL_CloseJoystick(g_virtual_joystick);
    g_virtual_joystick = nullptr;
  }
  if (g_virtual_id) {
    SDL_DetachVirtualJoystick(g_virtual_id);
    g_virtual_id = 0;
  }
}

void SetTouchStick(int stick, float x, float y) {
  std::lock_guard<std::mutex> lock(g_gamepad_mutex);
  SetStickLocked(stick == 0 ? 0 : 1, x, y);
}

void SetTouchTrigger(int side, float value) {
  std::lock_guard<std::mutex> lock(g_gamepad_mutex);
  SetTriggerLocked(side == 0 ? 0 : 1, value);
}

void SetTouchButton(int button, bool down) {
  std::lock_guard<std::mutex> lock(g_gamepad_mutex);
  SetButtonLocked(button, down);
}

}  // namespace dimensions::android

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_dimensions_TouchGamepadView_nativeSetStick(
    JNIEnv*, jclass, jint stick, jfloat x, jfloat y) {
  dimensions::android::SetTouchStick(stick, x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_dimensions_TouchGamepadView_nativeSetTrigger(
    JNIEnv*, jclass, jint side, jfloat value) {
  dimensions::android::SetTouchTrigger(side, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_dimensions_TouchGamepadView_nativeSetButton(
    JNIEnv*, jclass, jint button, jboolean down) {
  dimensions::android::SetTouchButton(button, down == JNI_TRUE);
}
