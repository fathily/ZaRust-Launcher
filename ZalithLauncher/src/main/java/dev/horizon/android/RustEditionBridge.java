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

    /**
     * Mirrors the one account selected in ZaRust into both the engine account store and the
     * Android launcher config. There is deliberately no second editable Rust player name.
     */
    public static String syncZalithAccount(
            Context context, String uuid, String name, String accessToken,
            String refreshToken, long expiresAt, String xuid, boolean microsoft) {
        try {
            ensureOfficialModsInstalled(context);
        } catch (IOException error) {
            return error.getMessage() == null ? "Gagal memasang mod resmi Rust" : error.getMessage();
        }
        String safeName = name == null ? "" : name.trim();
        String safeAccess = accessToken == null ? "" : accessToken;
        String safeRefresh = refreshToken == null ? "" : refreshToken;
        if (safeName.isEmpty()) return "Nama akun ZaRust kosong. Pilih akun dari menu Accounts.";
        if (microsoft && (safeAccess.isEmpty() || safeRefresh.isEmpty())) {
            return "Token Microsoft akun ZaRust belum tersedia. Segarkan akun di menu Accounts.";
        }
        try {
            JSONObject settings = readSettings(context);
            settings.put("name", safeName);
            settings.put("account_uuid", uuid == null ? "" : uuid);
            settings.put("account_type", microsoft ? "Microsoft" : "Local");
            writeSettings(context, settings);
        } catch (IOException | JSONException error) {
            return "Gagal menyimpan akun Rust: " + error.getMessage();
        }
        return nativeSyncZalithAccount(
                home(context).getAbsolutePath(),
                uuid == null ? "" : uuid,
                safeName,
                safeAccess,
                safeRefresh,
                expiresAt,
                xuid == null ? "" : xuid,
                microsoft);
    }

    private static final String[] OFFICIAL_MOD_IDS = {
            "fps_overlay", "keystrokes", "far_view", "minimap", "zoom", "recipes"
    };

    /**
     * Installs Starling's official Horizon .hmod components into Horizon's normal mod folder.
     * The component API is pinned to the same Horizon 0.1 mod contract; no Starling launcher or
     * second native engine is packaged.
     */
    public static void ensureOfficialModsInstalled(Context context) throws IOException {
        File home = home(context);
        File modDir = new File(home, "horizon-mods");
        if (!modDir.isDirectory() && !modDir.mkdirs()) {
            throw new IOException("Tidak bisa membuat folder mod Rust");
        }
        String[] bundled;
        try {
            bundled = context.getAssets().list("zarust-official-mods");
        } catch (IOException error) {
            throw new IOException("Mod resmi belum tersedia di APK ini", error);
        }
        if (bundled == null) bundled = new String[0];

        String version;
        try (InputStream input = context.getAssets().open("zarust-official-mods/version.txt")) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            version = count <= 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new IOException("Versi paket mod resmi tidak ditemukan", error);
        }
        if (version.isEmpty()) throw new IOException("Versi paket mod resmi kosong");

        File marker = new File(home, ".zarust-official-mods-version");
        String installedVersion = "";
        if (marker.isFile()) {
            try {
                installedVersion = new String(
                        java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                installedVersion = "";
            }
        }

        boolean allPresent = true;
        for (String id : OFFICIAL_MOD_IDS) {
            if (!new File(modDir, id + ".hmod").isFile()) {
                allPresent = false;
                break;
            }
        }
        if (version.equals(installedVersion) && allPresent) return;

        for (String id : OFFICIAL_MOD_IDS) {
            String assetName = id + ".hmod";
            if (!contains(bundled, assetName)) {
                throw new IOException("Paket mod resmi tidak lengkap: " + assetName);
            }
            File target = new File(modDir, assetName);
            File temporary = new File(modDir, assetName + ".partial");
            try (InputStream input = context.getAssets().open("zarust-official-mods/" + assetName);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("Gagal memasang mod " + id);
            }
        }
        File markerTemp = new File(home, ".zarust-official-mods-version.partial");
        try (FileOutputStream output = new FileOutputStream(markerTemp)) {
            output.write((version + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!markerTemp.renameTo(marker)) {
            markerTemp.delete();
            throw new IOException("Gagal menyimpan versi mod resmi");
        }
    }

    private static boolean contains(String[] values, String expected) {
        if (values == null) return false;
        for (String value : values) if (expected.equals(value)) return true;
        return false;
    }

    private static boolean isOfficialModId(String id) {
        if (id == null) return false;
        for (String allowed : OFFICIAL_MOD_IDS) if (allowed.equals(id)) return true;
        return false;
    }

    public static boolean isModEnabled(Context context, String id) {
        if (!isOfficialModId(id)) return false;
        File file = new File(home(context), "horizon-mods.txt");
        if (!file.isFile()) return false;
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (("mod." + id + ":true").equals(line.trim())) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /** Saves the mod toggle in Horizon's normal mod-list file; it takes effect next launch. */
    public static void setModEnabled(Context context, String id, boolean enabled) throws IOException {
        if (!isOfficialModId(id)) throw new IOException("ID mod tidak dikenal");
        ensureOfficialModsInstalled(context);
        File home = home(context);
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("Tidak bisa membuat penyimpanan Rust");
        }
        File file = new File(home, "horizon-mods.txt");
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (file.isFile()) {
            try {
                lines.addAll(java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new IOException("Tidak bisa membaca daftar mod Rust", error);
            }
        }
        String key = "mod." + id + ":";
        lines.removeIf(line -> line.trim().startsWith(key));
        if (enabled) lines.add(key + "true");
        File temporary = new File(home, "horizon-mods.txt.partial");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"))
                    .getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) {
            temporary.delete();
            throw new IOException("Gagal menyimpan daftar mod Rust");
        }
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
