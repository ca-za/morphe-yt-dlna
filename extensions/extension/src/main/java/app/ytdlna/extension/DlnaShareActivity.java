package app.ytdlna.extension;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Share target "Stream to DLNA device". Added to the patched app's manifest, so it shows up
 * in the share sheet and receives the shared video link. Runs in the app's own process,
 * so it can use the streams the app already loaded.
 */
public class DlnaShareActivity extends Activity {
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String text = getIntent() == null ? null : getIntent().getStringExtra(Intent.EXTRA_TEXT);
        String videoId = extractVideoId(text);
        Log.i("Share target opened for " + videoId);

        StreamInfo info = videoId == null ? null : StreamCapture.get(videoId);
        if (info == null) {
            Toast.makeText(this, DlnaCast.t(
                    "Bitte das Video zuerst in der App abspielen.",
                    "Please play the video in the app first."), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        DlnaCast.showPicker(this, null, info, this::finish);
    }

    static String extractVideoId(String text) {
        if (text == null) return null;
        Matcher m = VIDEO_ID.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
