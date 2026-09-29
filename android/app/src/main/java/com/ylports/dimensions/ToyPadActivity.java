package com.ylports.dimensions;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * In-app manager for the seven LEGO Dimensions ToyPad figure slots.
 *
 * Tap a slot to import a 180-byte NTAG213 dump. Long-press removes the figure
 * from the emulated pad without deleting its saved dump.
 */
public final class ToyPadActivity extends Activity {
    private static final int REQUEST_BASE = 2000;
    private final Button[] slots = new Button[7];
    private TextView status;
    private int pendingSlot = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refresh();
    }

    private View buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Toy Pad");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView info = new TextView(this);
        info.setText(
            "Toca un espacio para cargar una figura (.bin NTAG213, 180 bytes). " +
            "Mantén pulsado para quitarla del pad."
        );
        info.setTextSize(15);
        LinearLayout.LayoutParams infoParams = matchWrap();
        infoParams.topMargin = dp(10);
        root.addView(info, infoParams);

        addSection(root, "Centro", 0, 0);
        addSection(root, "Izquierda", 1, 3);
        addSection(root, "Derecha", 4, 6);

        status = new TextView(this);
        status.setTextSize(14);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(18);
        root.addView(status, statusParams);

        Button close = new Button(this);
        close.setText("Volver al juego");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = matchWrap();
        closeParams.topMargin = dp(16);
        root.addView(close, closeParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void addSection(LinearLayout root, String title, int first, int last) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hp = matchWrap();
        hp.topMargin = dp(18);
        root.addView(heading, hp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = matchWrap();
        rp.topMargin = dp(6);
        root.addView(row, rp);

        for (int slot = first; slot <= last; slot++) {
            final int target = slot;
            Button button = new Button(this);
            slots[slot] = button;
            button.setOnClickListener(v -> chooseFigure(target));
            button.setOnLongClickListener(v -> {
                removeFigure(target);
                return true;
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            );
            bp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(button, bp);
        }
    }

    private void chooseFigure(int slot) {
        pendingSlot = slot;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_BASE + slot);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        int slot = requestCode - REQUEST_BASE;
        if (slot < 0 || slot >= 7) return;

        Uri uri = data.getData();
        String label = displayName(uri);
        status.setText("Cargando " + label + "…");

        new Thread(() -> {
            String error = null;
            try {
                File target = ToyPadClient.slotFile(this, slot);
                copyExactTag(uri, target);
                ToyPadClient.load(this, slot);
                ToyPadClient.setActive(this, slot, true);
                ToyPadClient.setLabel(this, slot, label);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            final String finalError = error;
            runOnUiThread(() -> {
                status.setText(
                    finalError == null
                        ? "Figura colocada en " + slotName(slot) + "."
                        : "Error: " + finalError
                );
                refresh();
            });
        }, "ToyPadLoad").start();
    }

    private void copyExactTag(Uri uri, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            if (in == null) throw new IOException("No se pudo abrir la figura.");
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > ToyPadClient.TAG_SIZE) {
                    throw new IOException("El archivo supera 180 bytes.");
                }
                out.write(buffer, 0, read);
            }
            if (total != ToyPadClient.TAG_SIZE) {
                throw new IOException(
                    "El dump debe medir exactamente 180 bytes; mide " + total + "."
                );
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }

        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("No se pudo reemplazar la figura anterior.");
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("No se pudo guardar la figura.");
        }
    }

    private void removeFigure(int slot) {
        status.setText("Quitando " + slotName(slot) + "…");
        new Thread(() -> {
            String error = null;
            try {
                ToyPadClient.remove(slot);
                ToyPadClient.setActive(this, slot, false);
            } catch (IOException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                status.setText(
                    finalError == null ? "Figura retirada." : "Error: " + finalError
                );
                refresh();
            });
        }, "ToyPadRemove").start();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.isEmpty()) return value;
            }
        } catch (Exception ignored) {}
        return "figura.bin";
    }

    private void refresh() {
        for (int slot = 0; slot < slots.length; slot++) {
            Button button = slots[slot];
            if (button == null) continue;
            boolean active = ToyPadClient.isActive(this, slot);
            String label = ToyPadClient.getLabel(this, slot);
            if (label.isEmpty()) label = "Vacío";
            button.setText(slotName(slot) + "\n" + (active ? label : "Vacío"));
        }

        if (status != null && status.getText().length() == 0) {
            status.setText(
                ToyPadClient.canConnect()
                    ? "Toy Pad emulado conectado."
                    : "El Toy Pad todavía no responde. Vuelve al juego y abre esta pantalla otra vez."
            );
        }
    }

    private String slotName(int slot) {
        switch (slot) {
            case 0: return "Centro";
            case 1: return "Izq. 1";
            case 2: return "Izq. 2";
            case 3: return "Izq. 3";
            case 4: return "Der. 1";
            case 5: return "Der. 2";
            case 6: return "Der. 3";
            default: return "Slot " + slot;
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
