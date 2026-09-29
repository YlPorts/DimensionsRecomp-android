package com.ylports.dimensions;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Client for ReXGlue's loopback ToyPad protocol on 127.0.0.1:9191. */
final class ToyPadClient {
    static final int TAG_SIZE = 180;
    private static final int PORT = 9191;
    private static final int CONNECT_TIMEOUT_MS = 700;
    private static final String PREFS = "toypad_slots";

    private ToyPadClient() {}

    static int padForSlot(int slot) {
        if (slot == 0) return 1;      // centre
        if (slot <= 3) return 2;      // left
        return 3;                     // right
    }

    static File slotFile(Context context, int slot) {
        File dir = new File(GameFiles.root(context), "toypad");
        dir.mkdirs();
        return new File(dir, "slot_" + slot + ".bin");
    }

    static boolean isActive(Context context, int slot) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("active_" + slot, false);
    }

    static void setActive(Context context, int slot, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("active_" + slot, active)
            .apply();
    }

    static String getLabel(Context context, int slot) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("label_" + slot, "");
    }

    static void setLabel(Context context, int slot, String label) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("label_" + slot, label == null ? "" : label)
            .apply();
    }

    static byte[] readTag(File file) throws IOException {
        byte[] tag = new byte[TAG_SIZE];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < tag.length) {
                int read = in.read(tag, offset, tag.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != tag.length || in.read() != -1) {
                throw new IOException("La figura debe ser un dump NTAG213 de exactamente 180 bytes.");
            }
        }
        return tag;
    }

    static void load(Context context, int slot) throws IOException {
        File file = slotFile(context, slot);
        byte[] tag = readTag(file);
        byte[] path = file.getAbsolutePath().getBytes(StandardCharsets.UTF_8);
        if (path.length > 65535) {
            throw new IOException("Ruta de figura demasiado larga.");
        }

        try (Socket socket = connect();
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeByte(0x01);
            out.writeByte(padForSlot(slot));
            out.writeByte(slot);
            out.writeByte(0);
            out.writeByte(0);
            out.write(tag);
            out.writeByte(path.length & 0xFF);
            out.writeByte((path.length >>> 8) & 0xFF);
            out.write(path);
            out.flush();
        }
    }

    static void remove(int slot) throws IOException {
        try (Socket socket = connect();
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeByte(0x02);
            out.writeByte(padForSlot(slot));
            out.writeByte(slot);
            out.writeByte(0);
            out.writeByte(0);
            out.flush();
        }
    }

    static void restoreActive(Context context) {
        for (int slot = 0; slot < 7; slot++) {
            if (!isActive(context, slot) || !slotFile(context, slot).isFile()) {
                continue;
            }
            try {
                load(context, slot);
            } catch (IOException ignored) {
                // The native ToyPad listener may still be starting. MainActivity
                // retries the whole restore sequence shortly afterwards.
                return;
            }
        }
    }

    static boolean canConnect() {
        try (Socket ignored = connect()) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT),
            CONNECT_TIMEOUT_MS
        );
        socket.setSoTimeout(1500);
        return socket;
    }
}
