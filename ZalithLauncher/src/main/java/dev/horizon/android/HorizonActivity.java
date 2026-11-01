package dev.horizon.android;

/** NativeActivity host for the isolated Horizon Rust Edition engine. */
public final class HorizonActivity extends android.app.NativeActivity {
    static {
        System.loadLibrary("horizon_android");
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 4101) return;
        nativeDocumentResult(request, result,
                data == null || data.getData() == null ? null : data.getData().toString());
    }
}
