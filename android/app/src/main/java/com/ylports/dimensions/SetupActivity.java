package com.ylports.dimensions;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
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
import java.util.Locale;

/**
 * First-run launcher.
 *
 * Android's scoped storage prevents the native runtime from walking an
 * arbitrary folder selected by the user as a normal filesystem path. The
 * launcher therefore uses the Storage Access Framework to read the selected
 * tree and copies it into this app's own external-files directory, where the
 * native ReXGlue VFS can use ordinary paths without broad storage permission.
 */
public final class SetupActivity extends Activity {
    private static final int REQUEST_GAME = 1001;
    private static final int REQUEST_UPDATE = 1002;
    private static final int COPY_BUFFER_SIZE = 1024 * 1024;

    private TextView statusView;
    private Button importGameButton;
    private Button importUpdateButton;
    private Button startButton;
    private boolean busy;
    private long lastProgressUi;

    private static final class ImportStats {
        long bytes;
        int files;
        int directories;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GameFiles.prepare(this);
        setContentView(buildUi());
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildUi() {
        final int pad = dp(20);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Dimensions Recompiled");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText(
            "Importa tu copia extraída de LEGO Dimensions para Xbox 360. " +
            "La carpeta del juego debe contener Default.xex en su raíz. " +
            "TU23 puede importarse aparte."
        );
        description.setTextSize(16);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(14);
        content.addView(description, descriptionParams);

        importGameButton = new Button(this);
        importGameButton.setText("Importar juego base");
        importGameButton.setOnClickListener(v -> chooseTree(REQUEST_GAME));
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(20);
        content.addView(importGameButton, buttonParams);

        importUpdateButton = new Button(this);
        importUpdateButton.setText("Importar / reemplazar TU23");
        importUpdateButton.setOnClickListener(v -> chooseTree(REQUEST_UPDATE));
        LinearLayout.LayoutParams updateParams = matchWrap();
        updateParams.topMargin = dp(8);
        content.addView(importUpdateButton, updateParams);

        startButton = new Button(this);
        startButton.setText("Iniciar juego");
        startButton.setOnClickListener(v -> {
            if (!busy && GameFiles.hasBaseGame(this)) {
                startActivity(new Intent(this, MainActivity.class));
            }
        });
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(18);
        content.addView(startButton, startParams);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(20);
        content.addView(statusView, statusParams);

        TextView note = new TextView(this);
        note.setText(
            "Los archivos se copian a la carpeta privada de la app para que Android 11+ " +
            "no necesite acceso general al almacenamiento. Desinstalar la app puede borrar " +
            "esta copia; conserva tus archivos originales."
        );
        note.setTextSize(13);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(18);
        content.addView(note, noteParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
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

    private void chooseTree(int requestCode) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(intent, requestCode);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                treeUri,
                data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some document providers grant access only for this Activity
            // lifetime. The import still works immediately.
        }

        if (requestCode == REQUEST_GAME) {
            importTree(treeUri, true);
        } else if (requestCode == REQUEST_UPDATE) {
            importTree(treeUri, false);
        }
    }

    private void importTree(Uri treeUri, boolean game) {
        if (busy) return;
        busy = true;
        lastProgressUi = 0;
        refreshState();

        final String label = game ? "juego base" : "TU23";
        statusView.setText("Importando " + label + "…");

        Thread thread = new Thread(() -> {
            ImportStats stats = new ImportStats();
            String error = null;

            File root = GameFiles.root(this);
            File target = game ? GameFiles.gameDir(this) : GameFiles.updateDir(this);
            File staging = new File(root, target.getName() + ".importing");
            File backup = new File(root, target.getName() + ".backup");

            try {
                GameFiles.deleteRecursively(staging);
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IOException("No se pudo crear " + staging);
                }

                String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                copyChildren(treeUri, rootId, staging, stats);

                if (game && GameFiles.findDefaultXex(staging) == null) {
                    throw new IOException(
                        "La carpeta seleccionada no contiene Default.xex en su raíz."
                    );
                }

                GameFiles.deleteRecursively(backup);
                if (target.exists() && !target.renameTo(backup)) {
                    throw new IOException("No se pudo preparar la carpeta anterior.");
                }
                if (!staging.renameTo(target)) {
                    if (backup.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        backup.renameTo(target);
                    }
                    throw new IOException("No se pudo activar la importación terminada.");
                }
                GameFiles.deleteRecursively(backup);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                GameFiles.deleteRecursively(staging);
            }

            final String finalError = error;
            runOnUiThread(() -> {
                busy = false;
                if (finalError == null) {
                    statusView.setText(
                        String.format(
                            Locale.US,
                            "%s importado: %d archivos, %s.",
                            game ? "Juego base" : "TU23",
                            stats.files,
                            formatBytes(stats.bytes)
                        )
                    );
                } else {
                    statusView.setText("Error al importar " + label + ": " + finalError);
                }
                refreshState();
            });
        }, game ? "ImportGameData" : "ImportTitleUpdate");
        thread.start();
    }

    private void copyChildren(
        Uri treeUri,
        String parentDocumentId,
        File destination,
        ImportStats stats
    ) throws IOException {
        ContentResolver resolver = getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, parentDocumentId
        );

        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("El proveedor no permitió leer la carpeta.");
            }

            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                String safeName = safeName(displayName);
                if (safeName.isEmpty()) continue;

                File out = new File(destination, safeName);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    if (!out.mkdirs() && !out.isDirectory()) {
                        throw new IOException("No se pudo crear " + out.getName());
                    }
                    stats.directories++;
                    copyChildren(treeUri, documentId, out, stats);
                    continue;
                }

                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, documentId
                );
                try (
                    InputStream input = resolver.openInputStream(documentUri);
                    FileOutputStream output = new FileOutputStream(out, false)
                ) {
                    if (input == null) {
                        throw new IOException("No se pudo abrir " + displayName);
                    }
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        stats.bytes += read;
                        maybeShowProgress(stats);
                    }
                }
                stats.files++;
                maybeShowProgress(stats);
            }
        }
    }

    private String safeName(String name) {
        if (name == null) return "";
        String value = name.replace('/', '_').replace('\\', '_').replace("\u0000", "");
        if (value.equals(".") || value.equals("..")) {
            return "_" + value.replace('.', '_');
        }
        return value;
    }

    private void maybeShowProgress(ImportStats stats) {
        long now = SystemClock.uptimeMillis();
        if (now - lastProgressUi < 500) return;
        lastProgressUi = now;
        final int files = stats.files;
        final long bytes = stats.bytes;
        runOnUiThread(() -> {
            if (busy) {
                statusView.setText(
                    "Copiando… " + files + " archivos, " + formatBytes(bytes)
                );
            }
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private void refreshState() {
        if (statusView == null) return;
        boolean ready = GameFiles.hasBaseGame(this);
        importGameButton.setEnabled(!busy);
        importUpdateButton.setEnabled(!busy);
        startButton.setEnabled(!busy && ready);

        if (!busy && statusView.getText().length() == 0) {
            String path = GameFiles.root(this).getAbsolutePath();
            statusView.setText(
                ready
                    ? "Juego base detectado. Ya puedes iniciar.\n" + path
                    : "Falta importar el juego base.\nDestino: " + path
            );
        }
    }
}
