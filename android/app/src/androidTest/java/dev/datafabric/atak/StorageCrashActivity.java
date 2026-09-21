package dev.arachne.atak;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Test-only bootstrap: the standalone test APK omits the Kotlin runtime. */
public final class StorageCrashActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Isolated fabric storage crash check");
        setContentView(view);
        String run = getIntent().getStringExtra("run_id");
        String phase = getIntent().getStringExtra("phase");
        String boundary = getIntent().getStringExtra("boundary");
        if (run == null || !run.matches("[a-f0-9]{32}") || phase == null ||
                !phase.matches("setup|crash|verify") || boundary == null ||
                !boundary.matches("before_save|partial_write|after_save")) {
            finish();
            return;
        }
        File directory = new File(getNoBackupFilesDir(), "storage-crashes/" + run);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Test storage unavailable");
        new Thread(() -> {
            JSONObject result;
            try {
                Context plugin = createPackageContext("dev.arachne.atak", CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY);
                ClassLoader loader = new DexClassLoader(getApplicationInfo().sourceDir,
                        getCodeCacheDir().getPath(), null, plugin.getClassLoader());
                Class<?> type = loader.loadClass("dev.arachne.atak.StorageCrashCheck");
                Object check = type.getConstructor(Context.class).newInstance(this);
                result = (JSONObject) type.getMethod("runCheck", File.class, String.class, String.class)
                        .invoke(check, directory, phase, boundary);
                result.put("passed", true);
            } catch (Throwable error) {
                if (error instanceof InvocationTargetException) error = ((InvocationTargetException) error).getTargetException();
                result = new JSONObject();
                try { result.put("passed", false).put("error", error.toString()); }
                catch (Exception ignored) { throw new IllegalStateException(error); }
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, phase + ".json"))) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (Exception error) { throw new IllegalStateException(error); }
            runOnUiThread(this::finish);
        }).start();
    }
}
