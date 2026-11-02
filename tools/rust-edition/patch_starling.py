#!/usr/bin/env python3
"""Patch the pinned Starling source for embedding in ZaRust."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "StarlingEngine")


def replace_once(path: Path, old: str, new: str) -> None:
    value = path.read_text(encoding="utf-8")
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one patch anchor, got {count}")
    path.write_text(value.replace(old, new), encoding="utf-8")


activity = root / "packaging/android/java/io/github/juanluci/starling/StarlingActivity.java"
text = activity.read_text(encoding="utf-8")
for imp in [
    "import org.json.JSONArray;",
    "import org.json.JSONObject;",
    "import java.nio.charset.StandardCharsets;",
    "import java.util.Locale;",
]:
    if imp not in text:
        text = text.replace("import java.io.File;", "import java.io.File;\n" + imp, 1)

replace_once(
    activity,
    """    @Override
    protected void onCreate(Bundle savedInstanceState) {
        loadGame();""",
    """    @Override
    protected void onCreate(Bundle savedInstanceState) {
        syncZaRustAccount(getIntent());
        loadGame();""",
)

replace_once(
    activity,
    """    private void loadGame() {
        File build = NativeMods.chosen(this, versionName());""",
    """    private String nativeBuildVersion() {
        String version = versionName();
        return version != null && version.endsWith("-debug")
                ? version.substring(0, version.length() - "-debug".length())
                : version;
    }

    private void loadGame() {
        File build = NativeMods.chosen(this, nativeBuildVersion());""",
)

anchor = """    /**
     * Loads the game library with this class loader,"""
method = r'''
    /** Import the active ZaRust profile before the native game thread starts. */
    private void syncZaRustAccount(Intent launch) {
        String name = launch == null ? null : launch.getStringExtra("zarust_player_name");
        if (name == null || name.trim().isEmpty()) name = "Steve";
        name = name.trim();
        try {
            Os.setenv("HORIZON_PLAYER_NAME", name, true);
        } catch (Exception ignored) {
            // The engine retains its normal offline-name fallback.
        }

        String type = launch == null ? "" : launch.getStringExtra("zarust_account_type");
        String uuid = launch == null ? "" : launch.getStringExtra("zarust_profile_uuid");
        String access = launch == null ? "" : launch.getStringExtra("zarust_access_token");
        String refresh = launch == null ? "" : launch.getStringExtra("zarust_refresh_token");
        String xuid = launch == null ? "" : launch.getStringExtra("zarust_xuid");
        long expiresAt = launch == null ? 0L : launch.getLongExtra("zarust_expires_at", 0L);
        boolean microsoft = "Microsoft".equals(type)
                && uuid != null && !uuid.isEmpty()
                && access != null && !access.isEmpty() && !"0".equals(access)
                && refresh != null && !refresh.isEmpty() && !"0".equals(refresh);

        File accountDir = new File(getFilesDir(), "accounts");
        File secretsDir = new File(accountDir, "secrets");
        if (!secretsDir.isDirectory() && !secretsDir.mkdirs()) return;

        // Never let Local/Auth Server profiles reuse a prior Microsoft token.
        File[] oldSecrets = secretsDir.listFiles();
        if (oldSecrets != null) {
            for (File old : oldSecrets) {
                if (old.isFile() && old.getName().endsWith(".sealed")) old.delete();
            }
        }

        try {
            JSONObject root = new JSONObject();
            JSONArray accounts = new JSONArray();
            root.put("accounts", accounts);
            root.put("selected", JSONObject.NULL);
            if (microsoft) {
                String key = uuid.replace("-", "").toLowerCase(Locale.ROOT);
                JSONObject profile = new JSONObject();
                profile.put("uuid", key);
                profile.put("name", name);
                profile.put("skin", JSONObject.NULL);
                profile.put("cape", JSONObject.NULL);
                accounts.put(profile);
                root.put("selected", key);

                JSONObject secrets = new JSONObject();
                secrets.put("refresh_token", refresh);
                secrets.put("minecraft_token", access);
                secrets.put("minecraft_expires_at", Math.max(0L, expiresAt / 1000L));
                if (xuid != null && !xuid.isEmpty()) secrets.put("xuid", xuid);
                byte[] sealed = sealSecret(secrets.toString().getBytes(StandardCharsets.UTF_8));
                if (sealed == null || sealed.length == 0) {
                    throw new IllegalStateException("Android Keystore refused the ZaRust token");
                }
                writeZaRustFile(new File(secretsDir, key + ".sealed"), sealed);
            }
            writeZaRustFile(
                    new File(accountDir, "accounts.json"),
                    root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            android.util.Log.e("ZaRust-Starling", "Could not import the active ZaRust profile", error);
        }
    }

    private static void writeZaRustFile(File target, byte[] data) throws java.io.IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new java.io.IOException("Cannot create " + parent);
        }
        File temporary = new File(parent, target.getName() + ".partial");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
            output.write(data);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new java.io.IOException("Cannot replace " + target);
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new java.io.IOException("Cannot commit " + target);
        }
    }

'''
replace_once(activity, anchor, method + anchor)

replace_once(
    activity,
    """    static String sharedFolder(Context context) {
        boolean allowed = Build.VERSION.SDK_INT >= 30
                ? Environment.isExternalStorageManager()
                : context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
        String name = context.getPackageName().endsWith(".dev") ? "StarlingDev" : "Starling";
        return allowed
                ? new File(Environment.getExternalStorageDirectory(), name).getPath()
                : null;
    }""",
    """    static String sharedFolder(Context context) {
        // ZaRust owns this game directory; do not create a public Starling folder.
        File root = new File(context.getFilesDir(), "rust-edition");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Cannot create ZaRust private game directory: " + root);
        }
        return root.getAbsolutePath();
    }""",
)

oldUpdate = """    public boolean installUpdate(String name) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(UpdateProvider.uri(getPackageName(), name), UpdateProvider.TYPE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }"""
newUpdate = """    public boolean installUpdate(String name) {
        // ZaRust's updater owns application updates; never install the stand-alone Starling APK.
        return false;
    }"""
replace_once(activity, oldUpdate, newUpdate)
activity.write_text(text, encoding="utf-8")

replace_once(
    root / "crates/horizon-client/src/android_lib.rs",
    """    horizon_client::accounts::resume();
    winit::platform::android::set_input_hook(input::hook);""",
    """    horizon_client::accounts::resume();
    // Fetch and verify Starling's official game files once; interrupted downloads resume.
    if !horizon_client::accounts::files_root().join("starling-files.json").is_file() {
        horizon_client::accounts::download_files();
    }
    winit::platform::android::set_input_hook(input::hook);""",
)

replace_once(
    root / "crates/horizon-client/src/title.rs",
    """                // Starling's account, where the game signs in itself: in the top left corner,
                // clear of the logo, so vanilla's buttons stay as they are.
                if crate::accounts::in_game() {
                    buttons.push(b(4.0, 4.0, 120.0, &account::main_label(), true, 8));
                }""",
    """                // ZaRust provides the active profile before this native game is launched.
                // The Starling-owned account/login page is therefore not exposed here.""",
)

print("Starling source patches applied.")
