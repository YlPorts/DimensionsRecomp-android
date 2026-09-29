#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <SDL3/SDL_system.h>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cctype>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include <fmt/format.h>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/main_android.h>
#include <rex/memory.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#include "android_gamepad.h"

#if REX_PLATFORM_ANDROID

namespace {

constexpr char kAppIdentifier[] = "legodimensions";

#define DLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DimensionsRecomp", __VA_ARGS__)

std::string NativeLibraryDir() {
  Dl_info info{};
  if (dladdr(reinterpret_cast<void*>(&NativeLibraryDir), &info) && info.dli_fname) {
    std::filesystem::path p(info.dli_fname);
    return p.parent_path().string();
  }
  return {};
}

JavaVM* JavaVm() {
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  if (!env) {
    return nullptr;
  }
  JavaVM* vm = nullptr;
  return env->GetJavaVM(&vm) == JNI_OK ? vm : nullptr;
}

std::string AndroidExternalDir() {
  const char* path = SDL_GetAndroidExternalStoragePath();
  return path ? std::string(path) : std::string();
}

void EnsureDirectory(const std::filesystem::path& path) {
  std::error_code ec;
  std::filesystem::create_directories(path, ec);
}

bool HasDefaultXex(const std::filesystem::path& root) {
  std::error_code ec;
  for (const auto& entry : std::filesystem::directory_iterator(root, ec)) {
    if (!entry.is_regular_file(ec)) {
      continue;
    }
    std::string name = entry.path().filename().string();
    for (char& c : name) {
      c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    if (name == "default.xex") {
      return true;
    }
  }
  return false;
}

int RunDimensionsAndroid() {
  if (!SDL_InitSubSystem(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMEPAD)) {
    DLOGE("SDL_InitSubSystem failed: %s", SDL_GetError());
    return EXIT_FAILURE;
  }

  if (!dimensions::android::EnsureTouchGamepad()) {
    DLOGE("Touch gamepad could not be attached: %s", SDL_GetError());
  }

  const std::string external = AndroidExternalDir();
  if (external.empty()) {
    DLOGE("SDL_GetAndroidExternalStoragePath returned no path");
    return EXIT_FAILURE;
  }

  const std::filesystem::path root(external);
  const std::filesystem::path game_root = root / "game";
  const std::filesystem::path update_root = root / "update";
  const std::filesystem::path user_root = root / "userdata";
  const std::filesystem::path cache_root = root / "cache";
  const std::filesystem::path log_root = root / "logs";

  EnsureDirectory(game_root);
  EnsureDirectory(update_root);
  EnsureDirectory(user_root);
  EnsureDirectory(cache_root);
  EnsureDirectory(log_root);

  if (!HasDefaultXex(game_root)) {
    DLOGE("No Default.xex found directly under %s", game_root.string().c_str());
    return EXIT_FAILURE;
  }

  const std::string library_dir = NativeLibraryDir();
  rex::SetAndroidApplicationContext(JavaVm(), SDL_GetAndroidActivity(),
                                    library_dir.c_str());
  rex::thread::AndroidInitialize();
  rex::memory::AndroidInitialize();
  rex::filesystem::AndroidInitialize();

  std::vector<std::string> args;
  args.emplace_back(kAppIdentifier);
  args.emplace_back(fmt::format("--game_data_root={}", game_root.string()));
  args.emplace_back(fmt::format("--update_data_root={}", update_root.string()));
  args.emplace_back(fmt::format("--user_data_root={}", user_root.string()));
  args.emplace_back(fmt::format("--cache_root={}", cache_root.string()));
  args.emplace_back(fmt::format("--log_file={}", (log_root / "legodimensions.log").string()));

  // Android is Vulkan-only in this port. The Xenos backend is a runtime-loaded
  // shared library packaged next to libmain.so.
  args.emplace_back("--gpu_plugin=xenos");
  args.emplace_back("--gpu_backend=vulkan");
  args.emplace_back("--fullscreen=true");

  // SDL handles both physical Android gamepads and the touch bridge we'll add
  // on top. ToyPad protocol emulation stays native in ReXGlue.
  args.emplace_back("--input_backend=sdl");
  args.emplace_back("--toypad_emulation=true");

  // Desktop companion processes do not exist on Android.
  args.emplace_back("--discord_rpc=false");
  args.emplace_back("--updates_check=false");
  args.emplace_back("--toypad_app_autostart=false");

  // Let Android's big.LITTLE scheduler move guest threads freely.
  args.emplace_back("--ignore_thread_affinities=true");
  args.emplace_back("--ignore_thread_priorities=true");

  std::vector<char*> argv;
  argv.reserve(args.size());
  for (auto& arg : args) {
    argv.push_back(arg.data());
  }

  auto remaining = rex::cvar::Init(static_cast<int>(argv.size()), argv.data());
  (void)remaining;
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();

  REXLOG_INFO("Dimensions Android bootstrap");
  REXLOG_INFO("game_root={}", game_root.string());
  REXLOG_INFO("update_root={}", update_root.string());
  REXLOG_INFO("user_root={}", user_root.string());
  REXLOG_INFO("native_library_dir={}", library_dir);

  int result = EXIT_FAILURE;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      REXLOG_ERROR("SDLWindowedAppContext initialization failed: {}", SDL_GetError());
      return EXIT_FAILURE;
    }

    const auto creator = rex::ui::WindowedApp::GetCreator(kAppIdentifier);
    if (!creator) {
      REXLOG_ERROR("ReX app '{}' is not registered", kAppIdentifier);
      return EXIT_FAILURE;
    }

    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);
    if (app->OnInitialize()) {
      result = app_context.RunMainMessageLoop();
    } else {
      REXLOG_ERROR("LegodimensionsApp::OnInitialize failed");
    }
    app->InvokeOnDestroy();
  }

  dimensions::android::ShutdownTouchGamepad();
  return result;
}

}  // namespace

int main(int argc, char** argv) {
  (void)argc;
  (void)argv;
  return RunDimensionsAndroid();
}

#endif  // REX_PLATFORM_ANDROID
