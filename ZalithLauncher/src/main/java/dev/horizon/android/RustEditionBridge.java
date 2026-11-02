package dev.horizon.android;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.io.File;

import com.movtery.zalithlauncher.game.account.Account;

/** ZaRust-owned entry point for the embedded Starling game engine. */
public final class RustEditionBridge {
    private static final String PREFS = "zarust_rust_edition";

    private RustEditionBridge() {}

    /**
     * Keep the complete Rust Edition game tree inside this application's private sandbox.
     * On Android this is files/rust-edition beneath package com.zarust.launcher.
     */
    public static File gameHome(Context context) {
        File folder = new File(context.getFilesDir(), "rust-edition");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("Cannot create private ZaRust game directory: " + folder);
        }
        return folder;
    }

    public static File modsDirectory(Context context) {
        return new File(gameHome(context), "horizon-mods");
    }

    public static File shaderPacksDirectory(Context context) {
        return new File(gameHome(context), "shaderpacks");
    }

    /** Native mod builds are stored by Starling in filesDir/native as .so + metadata. */
    public static File nativeModsDirectory(Context context) {
        return new File(context.getFilesDir(), "native");
    }

    public static String getInputMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("input_mode", "zalith");
    }

    public static void setInputMode(Context context, String mode) {
        String safe = "starling".equals(mode) ? "starling" : "zalith";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("input_mode", safe).apply();
    }

    /** Starts Starling in the same application and passes the selected ZaRust account. */
    public static void launch(Context context, Account account) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "io.github.juanluci.starling.StarlingActivity");
            intent.putExtra("zarust_input_mode", getInputMode(context));
            if (account != null) {
                intent.putExtra("zarust_player_name", account.getUsername());
                intent.putExtra("zarust_profile_uuid", account.getProfileId());
                intent.putExtra("zarust_account_type", account.getAccountType());
                intent.putExtra("zarust_access_token", account.getAccessToken());
                intent.putExtra("zarust_refresh_token", account.getRefreshToken());
                intent.putExtra("zarust_expires_at", account.getExpiresAt());
                intent.putExtra("zarust_xuid", account.getXUid());
            } else {
                intent.putExtra("zarust_player_name", "Steve");
                intent.putExtra("zarust_account_type", "Local");
            }
            context.startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(
                    context,
                    "Gagal membuka Starling Rust Edition: " + error.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
