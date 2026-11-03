package dev.horizon.android;

import android.app.NativeActivity;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;

/**
 * Native game surface only. The launcher, installation flow, navigation and
 * settings all belong to ZaRust's Compose UI.
 */
public final class HorizonActivity extends NativeActivity {
    private void loadSelectedRustRuntime() {
        // Load a locally compiled library before NativeActivity processes android.app.lib_name.
        // The selected file has the same native entry point and SONAME as the bundled runtime.
        File customRuntime = RustNativeBuildManager.INSTANCE.currentLibrary(this);
        if (customRuntime != null) {
            System.load(customRuntime.getAbsolutePath());
        } else {
            System.loadLibrary("horizon_android");
        }
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    @Override
    protected void onCreate(Bundle state) {
        loadSelectedRustRuntime();
        File rustHome = new File(getFilesDir(), "rust-edition");
        if (!rustHome.isDirectory() && !rustHome.mkdirs()) {
            throw new IllegalStateException("Cannot create Rust Edition storage");
        }
        try {
            new File(rustHome, "launch-from-zarust").createNewFile();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare Rust Edition launch", error);
        }
        super.onCreate(state);
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 4101) return;
        nativeDocumentResult(request, result,
                data == null || data.getData() == null ? null : data.getData().toString());
    }
}
