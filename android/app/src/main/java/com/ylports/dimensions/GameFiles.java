package com.ylports.dimensions;

import android.content.Context;

import java.io.File;

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

    static File findDefaultXex(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().equalsIgnoreCase("default.xex")) {
                return file;
            }
        }
        return null;
    }

    static boolean hasBaseGame(Context context) {
        return findDefaultXex(gameDir(context)) != null;
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
