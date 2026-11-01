package dev.horizon.android;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Calls the Horizon native installer from ZaRust's own Compose screens.
 * The Horizon launcher UI is not used for installation or settings.
 */
public final class RustEditionBridge {
    private RustEditionBridge() {}

    static {
        System.loadLibrary("horizon_android");
    }

    private static native boolean nativeIsReady(String homePath);
    private static native String nativeInstall(String homePath, String cachePath, String progressPath);
    private static native String nativeSyncZalithAccount(
            String homePath, String uuid, String name, String accessToken,
            String refreshToken, long expiresAt, String xuid, boolean microsoft);

    public static File home(Context context) {
        return new File(context.getFilesDir(), "rust-edition");
    }

    private static void ensureEngineData(Context context, File home) throws IOException {
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Cannot create Rust Edition storage");
        }
        File target = new File(home, "engine-data.zip");
        if (target.isFile() && target.length() > 0) return;

        File temporary = new File(home, "engine-data.partial");
        try (InputStream input = context.getAssets().open("engine-data.zip");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot stage Horizon engine data");
        }
    }

    public static boolean isReady(Context context) {
        File home = home(context);
        return new File(home, "engine-data.zip").isFile()
                && nativeIsReady(home.getAbsolutePath());
    }

    /** Runs on an IO dispatcher; progress is published as a small JSON file. */
    public static String install(Context context) throws IOException {
        File home = home(context);
        ensureEngineData(context, home);
        File progress = new File(home, "install-progress.json");
        String error = nativeInstall(
                home.getAbsolutePath(),
                new File(context.getCacheDir(), "rust-edition").getAbsolutePath(),
                progress.getAbsolutePath());
        return error == null ? "Native installer returned no result" : error;
    }

    public static JSONObject readProgress(Context context) {
        File file = new File(home(context), "install-progress.json");
        if (!file.isFile()) return new JSONObject();
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** Syncs the account currently selected in Zalith without asking the user to sign in again. */
    public static String syncZalithAccount(
            Context context, String uuid, String name, String accessToken,
            String refreshToken, long expiresAt, String xuid, boolean microsoft) {
        return nativeSyncZalithAccount(
                home(context).getAbsolutePath(),
                uuid == null ? "" : uuid,
                name == null ? "" : name,
                accessToken == null ? "" : accessToken,
                refreshToken == null ? "" : refreshToken,
                expiresAt,
                xuid == null ? "" : xuid,
                microsoft);
    }

    public static JSONObject readSettings(Context context) {
        File file = new File(home(context), "android-launcher.json");
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static void writeSettings(Context context, JSONObject settings) throws IOException {
        File home = home(context);
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Cannot create Rust Edition settings directory");
        }
        File target = new File(home, "android-launcher.json");
        File temporary = new File(home, "android-launcher.partial");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(settings.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (JSONException error) {
            throw new IOException("Cannot serialize Rust Edition settings", error);
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot save Rust Edition settings");
        }
    }
}
