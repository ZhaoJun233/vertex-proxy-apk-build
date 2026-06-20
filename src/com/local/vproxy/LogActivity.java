package com.local.vproxy;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LogActivity extends Activity {
    private static final int MAX_LOG_CHARS = 30000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView textView;
    private ScrollView scrollView;
    private String lastShownText = "";
    private boolean stickToBottom = true;
    private boolean userTouching = false;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshLogs();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable releaseTouchTask = new Runnable() {
        @Override
        public void run() {
            userTouching = false;
            updateStickToBottom();
            refreshLogs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scrollView = new ScrollView(this);
        textView = new TextView(this);
        int pad = dp(12);
        textView.setPadding(pad, pad, pad, pad);
        textView.setTextSize(12);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextIsSelectable(false);
        scrollView.addView(textView);
        scrollView.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                userTouching = true;
                handler.removeCallbacks(releaseTouchTask);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(releaseTouchTask);
                handler.postDelayed(releaseTouchTask, 600);
            }
            return false;
        });
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (!userTouching) {
                    updateStickToBottom();
                }
            });
        }
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTask.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        handler.removeCallbacks(releaseTouchTask);
        super.onPause();
    }

    private void refreshLogs() {
        String text = readAllLogs();
        int start = Math.max(0, text.length() - MAX_LOG_CHARS);
        String nextText = text.substring(start);
        if (nextText.equals(lastShownText)) {
            if (stickToBottom && !userTouching) {
                scrollToBottom();
            }
            return;
        }

        boolean follow = stickToBottom && !userTouching;
        int oldScrollY = scrollView.getScrollY();
        lastShownText = nextText;
        textView.setText(nextText);
        scrollView.post(() -> {
            if (follow) {
                scrollToBottom();
            } else {
                scrollView.scrollTo(0, Math.min(oldScrollY, maxScrollY()));
            }
            updateStickToBottom();
        });
    }

    private String readAllLogs() {
        StringBuilder all = new StringBuilder();
        appendFile(all, "wrapper.log");
        appendFile(all, "vproxy.log");
        if (all.length() == 0) {
            return "\u6682\u65e0\u65e5\u5fd7\u3002\u8bf7\u5148\u542f\u52a8\u670d\u52a1\u3002";
        }
        return all.toString();
    }

    private void appendFile(StringBuilder all, String name) {
        File file = new File(getFilesDir(), name);
        if (!file.exists()) {
            return;
        }
        try {
            all.append("=== ").append(name).append(" ===\n");
            all.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            all.append("\n\n");
        } catch (Exception e) {
            all.append("\u8bfb\u53d6 ").append(name).append(" \u5931\u8d25: ").append(e.getMessage()).append("\n\n");
        }
    }

    private void updateStickToBottom() {
        stickToBottom = maxScrollY() - scrollView.getScrollY() <= dp(32);
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.scrollTo(0, maxScrollY()));
    }

    private int maxScrollY() {
        if (scrollView.getChildCount() == 0) {
            return 0;
        }
        return Math.max(0, scrollView.getChildAt(0).getHeight() - scrollView.getHeight());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
