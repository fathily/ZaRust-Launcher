package dev.horizon.android;

import android.app.NativeActivity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

/** Native Horizon game host launched as ZaRust's Rust Edition. */
public final class HorizonActivity extends NativeActivity {
    static {
        System.loadLibrary("horizon_android");
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    @Override
    protected void onCreate(Bundle state) {
        // NativeActivity starts android_main during super.onCreate, so write the
        // one-shot launch marker first. Horizon consumes it from its isolated home.
        File rustHome = new File(getFilesDir(), "rust-edition");
        if (!rustHome.isDirectory() && !rustHome.mkdirs()) {
            throw new IllegalStateException("Cannot create Rust Edition storage");
        }
        try {
            new File(rustHome, "launch-from-zarust").createNewFile();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare Rust Edition launch", e);
        }

        super.onCreate(state);
        installBackOverlay();
    }

    private void installBackOverlay() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null) return;

        TextView back = new TextView(this);
        back.setText("←  ZaRust");
        back.setTextColor(Color.WHITE);
        back.setTextSize(14);
        back.setGravity(Gravity.CENTER);
        int horizontal = dp(14);
        int vertical = dp(9);
        back.setPadding(horizontal, vertical, horizontal, vertical);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC202127);
        background.setCornerRadius(dp(12));
        back.setBackground(background);
        back.setElevation(dp(8));
        back.setContentDescription("Back to ZaRust Launcher");
        back.setOnClickListener(view -> {
            long now = android.os.SystemClock.uptimeMillis();
            dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
            dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        params.setMargins(dp(14), dp(14), 0, 0);
        content.addView(back, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 4101) return;
        nativeDocumentResult(request, result,
                data == null || data.getData() == null ? null : data.getData().toString());
    }
}
