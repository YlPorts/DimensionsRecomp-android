package com.ylports.dimensions;

import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * SDL3 host activity for Dimensions Recompiled.
 *
 * The bootstrap intentionally keeps data inside the app-specific external
 * directory so Android 11+ scoped storage does not require broad storage
 * permissions. A proper SAF importer will be layered on top later.
 */
public final class MainActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            window.setSustainedPerformanceMode(true);
        }

        File root = getExternalFilesDir(null);
        if (root != null) {
            new File(root, "game").mkdirs();
            new File(root, "update").mkdirs();
            new File(root, "userdata").mkdirs();
            new File(root, "cache").mkdirs();
            new File(root, "logs").mkdirs();

            File defaultXex = new File(root, "game/default.xex");
            if (!defaultXex.isFile()) {
                Toast.makeText(
                    this,
                    "Dimensions Recompiled: falta game/default.xex en " + root.getAbsolutePath(),
                    Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    @Override
    protected String[] getLibraries() {
        // SDL is linked statically into the native target; libmain.so is the
        // Android entry point and loads librexgpu-xenos.so at runtime.
        return new String[] {"main"};
    }

    /**
     * Bridge used by the Android ReXGlue filesystem glue for content:// URIs.
     * It is already useful for individual imported files and will back the SAF
     * setup UI in the next milestone.
     */
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
