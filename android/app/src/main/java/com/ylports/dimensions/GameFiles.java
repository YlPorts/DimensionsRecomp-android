package com.ylports.dimensions;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Shared Android data layout and validation helpers. */
final class GameFiles {
    private GameFiles() {}

    static File root(Context context) {
        File root = context.getExternalFilesDir(null);
        return root != null ? root : context.getFilesDir();
    }

    static File gameDir(Context context) {
        return new File(root(context), "game");
    }

    static File updateDir(Context context) {
        return new File(root(context), "update");
    }

    static File userDataDir(Context context) {
        return new File(root(context), "userdata");
    }

    static File cacheDir(Context context) {
        return new File(root(context), "cache");
    }

    static File logsDir(Context context) {
        return new File(root(context), "logs");
    }

    static void prepare(Context context) {
        gameDir(context).mkdirs();
        updateDir(context).mkdirs();
        userDataDir(context).mkdirs();
        cacheDir(context).mkdirs();
        logsDir(context).mkdirs();
    }

    private static File findFileIgnoreCase(File directory, String name) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().equalsIgnoreCase(name)) {
                return file;
            }
        }
        return null;
    }

    static File findDefaultXex(File directory) {
        return findFileIgnoreCase(directory, "default.xex");
    }

    static File findDefaultXexp(File directory) {
        return findFileIgnoreCase(directory, "default.xexp");
    }

    static boolean hasBaseGame(Context context) {
        return findDefaultXex(gameDir(context)) != null;
    }

    static boolean hasExecutablePatch(Context context) {
        return findDefaultXexp(gameDir(context)) != null;
    }

    /**
     * ReXGlue patches game:\\default.xex by looking for the sibling
     * game:\\default.xexp. The update device is mounted separately, so an
     * extracted TU23 tree alone is not enough: mirror its Default.xexp into the
     * base-game root after every import.
     */
    static boolean syncExecutablePatchFromUpdate(Context context) throws IOException {
        File source = findDefaultXexp(updateDir(context));
        if (source == null) return false;

        File destination = new File(gameDir(context), "default.xexp");
        File temp = new File(gameDir(context), "default.xexp.copying");
        byte[] buffer = new byte[1024 * 1024];

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temp, false)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }

        if (destination.exists() && !destination.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("No se pudo reemplazar default.xexp anterior.");
        }
        if (!temp.renameTo(destination)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("No se pudo instalar default.xexp de TU23.");
        }
        return true;
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
