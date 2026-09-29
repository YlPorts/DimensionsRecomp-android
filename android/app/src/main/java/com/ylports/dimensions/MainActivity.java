package com.ylports.dimensions;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Window;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/** SDL3 activity hosting the native ReXGlue game runtime. */
public final class MainActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // SetupActivity is the normal launcher, but keep this guard for direct
        // launches from adb / recents after the app data has been cleared.
        if (!GameFiles.hasBaseGame(this)) {
            super.onCreate(savedInstanceState);
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        GameFiles.prepare(this);
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            window.setSustainedPerformanceMode(true);
        }
    }

    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into libmain.so by ReXGlue.
        return new String[] {"main"};
    }

    /** Content-URI bridge used by ReXGlue's Android filesystem glue. */
    public static ParcelFileDescriptor openContentFd(String uri, String mode) {
        SDLActivity self = mSingleton;
        if (self == null || uri == null) {
            return null;
        }
        try {
            return self.getContentResolver().openFileDescriptor(
                Uri.parse(uri), mode == null ? "r" : mode
            );
        } catch (Exception ignored) {
            return null;
        }
    }
}
