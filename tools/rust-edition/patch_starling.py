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


# Draw progress from ZaRust over Starling's native setup card.
download_activity = r"""

    /** Current first-run download/preparation state, supplied by android_lib.rs. */
    private static native String zarustDownloadState();

    private final Handler zarustHandler = new Handler(Looper.getMainLooper());
    private View zarustDownloadOverlay;
    private TextView zarustDownloadTitle;
    private TextView zarustDownloadBody;
    private TextView zarustDownloadCount;
    private ProgressBar zarustDownloadProgress;
    private int zarustWaitPolls;

    private final Runnable zarustDownloadPoll = new Runnable() {
        @Override
        public void run() {
            pollZaRustDownload();
        }
    };

    private int zarustDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView zarustText(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private void installZaRustDownloadOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xCC0B0C10);
        overlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(zarustDp(28), zarustDp(24), zarustDp(28), zarustDp(24));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(0xFF1B1C22);
        cardBackground.setCornerRadius(zarustDp(24));
        cardBackground.setStroke(zarustDp(1), 0xFF343640);
        card.setBackground(cardBackground);
        card.setElevation(zarustDp(20));

        TextView brand = zarustText("ZARUST  •  RUST EDITION", 12, 0xFFAAA6FF, true);
        TextView title = zarustText("Menyiapkan ZaRust", 23, 0xFFF4F3FA, true);
        TextView body = zarustText(
                "Mengunduh dan memverifikasi file game resmi untuk perangkat ini.",
                15, 0xFFC7C7D0, false);
        ProgressBar progress = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF8275F5));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF353641));
        TextView count = zarustText("Memeriksa file game…", 13, 0xFFA8A8B4, false);

        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(brand, line);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = zarustDp(12);
        card.addView(title, titleParams);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = zarustDp(8);
        card.addView(body, bodyParams);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, zarustDp(8));
        progressParams.topMargin = zarustDp(20);
        card.addView(progress, progressParams);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = zarustDp(12);
        card.addView(count, countParams);

        int available = getResources().getDisplayMetrics().widthPixels - zarustDp(40);
        int cardWidth = Math.min(zarustDp(660), Math.max(zarustDp(280), available));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, cardParams);
        ((ViewGroup) getWindow().getDecorView()).addView(
                overlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        zarustDownloadOverlay = overlay;
        zarustDownloadTitle = title;
        zarustDownloadBody = body;
        zarustDownloadCount = count;
        zarustDownloadProgress = progress;
        zarustHandler.post(zarustDownloadPoll);
    }

    private long zarustNumber(String[] fields, int index) {
        try {
            return Long.parseLong(fields[index]);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String zarustMb(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1000000.0);
    }

    private void pollZaRustDownload() {
        if (isFinishing() || zarustDownloadOverlay == null) return;

        final String state;
        try {
            state = zarustDownloadState();
        } catch (Throwable error) {
            zarustDownloadOverlay.setVisibility(View.GONE);
            return;
        }
        if (state == null || state.isEmpty()) {
            zarustDownloadOverlay.setVisibility(View.GONE);
            return;
        }

        String[] fields = state.split("\\|", 5);
        String kind = fields[0];
        boolean active = true;
        boolean indeterminate = true;
        int value = 0;

        if ("checking".equals(kind)) {
            zarustDownloadTitle.setText("Memeriksa file game");
            zarustDownloadBody.setText("ZaRust sedang memeriksa file Minecraft di penyimpanan privat aplikasi.");
            zarustDownloadCount.setText("Memeriksa kelengkapan file…");
        } else if ("downloading".equals(kind)) {
            long files = zarustNumber(fields, 1);
            long filesTotal = zarustNumber(fields, 2);
            long bytes = zarustNumber(fields, 3);
            long bytesTotal = zarustNumber(fields, 4);
            zarustDownloadTitle.setText("Mengunduh file Minecraft");
            zarustDownloadBody.setText("Mengambil file resmi dari server Mojang dan memverifikasi setiap file.");
            zarustDownloadCount.setText(
                    files + " dari " + filesTotal + " file  •  " +
                    zarustMb(bytes) + " dari " + zarustMb(bytesTotal));
            if (bytesTotal > 0) {
                indeterminate = false;
                value = (int) Math.max(0L, Math.min(1000L, bytes * 1000L / bytesTotal));
            }
        } else if ("java".equals(kind)) {
            long bytes = zarustNumber(fields, 1);
            long total = zarustNumber(fields, 2);
            zarustDownloadTitle.setText("Menyiapkan Java Runtime");
            zarustDownloadBody.setText("Mengunduh runtime yang dibutuhkan Rust Edition.");
            zarustDownloadCount.setText(zarustMb(bytes) + " dari " + zarustMb(total));
            if (total > 0) {
                indeterminate = false;
                value = (int) Math.max(0L, Math.min(1000L, bytes * 1000L / total));
            }
        } else if ("unpacking".equals(kind)) {
            zarustDownloadTitle.setText("Memasang Java Runtime");
            zarustDownloadBody.setText("Mengekstrak runtime ke penyimpanan privat ZaRust.");
            zarustDownloadCount.setText("Jangan tutup ZaRust selama proses ini.");
        } else if ("preparing".equals(kind)) {
            zarustDownloadTitle.setText("Menyiapkan Rust Edition");
            zarustDownloadBody.setText(fields.length > 1 && !fields[1].isEmpty()
                    ? fields[1] : "Mengatur resource game…");
            zarustDownloadCount.setText("Hampir selesai");
        } else if ("ready".equals(kind)) {
            zarustDownloadTitle.setText("File game sudah siap");
            zarustDownloadBody.setText("ZaRust sedang menyiapkan runtime dan resource.");
            zarustDownloadCount.setText("Memuat tahap berikutnya…");
            indeterminate = false;
            value = 1000;
            active = zarustWaitPolls < 12;
            zarustWaitPolls++;
        } else if ("missing".equals(kind)) {
            zarustWaitPolls++;
            active = zarustWaitPolls < 32;
        } else {
            // Let the engine's own retry/error screen handle failures and completed games.
            active = false;
        }

        if (active && !"missing".equals(kind)) {
            zarustDownloadProgress.setIndeterminate(indeterminate);
            if (!indeterminate) zarustDownloadProgress.setProgress(value);
            zarustDownloadOverlay.setVisibility(View.VISIBLE);
        } else if (!active) {
            zarustDownloadOverlay.setVisibility(View.GONE);
            return;
        }

        zarustHandler.postDelayed(zarustDownloadPoll, 350L);
    }


"""
replace_once(
    activity,
    """    /** Android took back the pointer the game had captured (android_lib.rs). */
    private static native void onPointerCaptureLost();""",
    """    /** Android took back the pointer the game had captured (android_lib.rs). */
    private static native void onPointerCaptureLost();

    private static native String zarustDownloadState();""",
)
replace_once(
    activity,
    """        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged""",
    """        hideSystemBars();
        installZaRustDownloadOverlay();
    }

    @Override
    public void onWindowFocusChanged""",
)
replace_once(
    activity,
    """    /**
     * Capture the mouse for the game's mouse look""",
    download_activity + """    /**
     * Capture the mouse for the game's mouse look""",
)
replace_once(
    activity,
    """    protected void onDestroy() {
        // The world was saved when the game left the screen""",
    """    protected void onDestroy() {
        zarustHandler.removeCallbacks(zarustDownloadPoll);
        // The world was saved when the game left the screen""",
)

android_lib = root / "crates/horizon-client/src/android_lib.rs"
android_text = android_lib.read_text(encoding="utf-8")
replace_once(
    android_lib,
    """static SHARED_FOLDER: OnceLock<PathBuf> = OnceLock::new();""",
    """static SHARED_FOLDER: OnceLock<PathBuf> = OnceLock::new();

""" + rustState,
)

print("Starling source patches applied.")
