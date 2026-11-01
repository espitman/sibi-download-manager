package com.espitman.sdm.web;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Independent test-APK recipient; handles only the synthetic fixture MIME. */
public final class PhoneHandoffTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextSize(20);
        text.setPadding(24, 48, 24, 24);
        setContentView(text);
        try {
            Intent input = getIntent();
            Uri uri = Intent.ACTION_VIEW.equals(input.getAction()) ? input.getData() : input.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null || !"content".equals(uri.getScheme()) || !"com.espitman.sdm.files".equals(uri.getAuthority())) throw new IllegalArgumentException("Expected SDM content URI");
            String name = uri.getLastPathSegment();
            if (name == null || !name.startsWith("sdm-web-phone-") || !name.endsWith(".sdmqa")) throw new IllegalArgumentException("Expected owned fixture");
            String nonce = name.substring("sdm-web-phone-".length(), name.length() - ".sdmqa".length());
            String payload;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                byte[] bytes = new byte[256];
                int length = stream.read(bytes);
                if (length <= 0 || stream.read() != -1) throw new IllegalArgumentException("Unexpected fixture size");
                payload = new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            text.setText("SDM test viewer\n\n" + payload);
            sendBroadcast(new Intent("com.espitman.sdm.test.HANDOFF_" + nonce).setPackage("com.espitman.sdm")
                .putExtra("payload", payload).putExtra("action", input.getAction()).putExtra("uri", uri.toString())
                .putExtra("readGrant", (input.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
                .putExtra("clip", input.getClipData() != null && input.getClipData().getItemCount() == 1 && uri.equals(input.getClipData().getItemAt(0).getUri())));
        } catch (Exception error) {
            text.setText("SDM fixture could not be opened: " + error.getClass().getSimpleName());
        }
    }
}
