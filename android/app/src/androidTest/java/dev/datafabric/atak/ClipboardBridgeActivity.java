package dev.arachne.atak;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.PersistableBundle;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only foreground bridge. Platform APIs keep it independent of the Kotlin
 * runtime normally supplied by the instrumented app. No fabric logic lives here. */
public final class ClipboardBridgeActivity extends Activity {
    private boolean handled;

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || handled) return;
        handled = true;
        File file = new File(getNoBackupFilesDir(), "test-invitation-link");
        String result = "failed";
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String action = getIntent().getStringExtra("clipboard_action");
            if ("read".equals(action)) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() != 1 || clip.getItemAt(0).getText() == null)
                    throw new IllegalStateException("Missing invitation");
                String link = clip.getItemAt(0).getText().toString();
                validate(link);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(link.getBytes(StandardCharsets.UTF_8));
                }
            } else if ("write".equals(action) || "write_text".equals(action) || "open".equals(action)) {
                byte[] bytes = new byte[65537];
                int count = 0;
                try (FileInputStream input = new FileInputStream(file)) {
                    while (count < bytes.length) {
                        int n = input.read(bytes, count, bytes.length - count);
                        if (n < 0) break;
                        count += n;
                    }
                }
                if (count < 1 || count > 65536) throw new IllegalStateException("Invalid size");
                String link = new String(bytes, 0, count, StandardCharsets.UTF_8);
                if (!"write_text".equals(action)) validate(link);
                if ("open".equals(action)) {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(link)));
                } else {
                    ClipData clip = ClipData.newPlainText("Workspace invitation", link);
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                    clipboard.setPrimaryClip(clip);
                }
                file.delete();
            } else throw new IllegalStateException("Unknown action");
            result = "ok";
        } catch (Exception error) {
            file.delete();
        } finally {
            AtomicFile target = new AtomicFile(new File(getNoBackupFilesDir(), "test-clipboard-result"));
            FileOutputStream output = null;
            try {
                output = target.startWrite();
                output.write(result.getBytes(StandardCharsets.UTF_8));
                target.finishWrite(output);
            } catch (Exception error) {
                if (output != null) target.failWrite(output);
            }
            finish();
        }
    }

    private static void validate(String link) {
        if (link.length() > 65536 || !(link.startsWith("arachne://join#") || link.startsWith("datafabric://join#") || (link.length() <= 23000 && link.startsWith("datafabric://request#"))))
            throw new IllegalStateException("Invalid invitation envelope");
    }
}
