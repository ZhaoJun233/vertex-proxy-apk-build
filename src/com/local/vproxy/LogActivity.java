package com.local.vproxy;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LogActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView textView;
    private ScrollView scrollView;
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshLogs();
            handler.postDelayed(this, 1000);
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
        textView.setTextIsSelectable(true);
        scrollView.addView(textView);
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
        super.onPause();
    }

    private void refreshLogs() {
        String text = readAllLogs();
        int start = Math.max(0, text.length() - 30000);
        textView.setText(text.substring(start));
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String readAllLogs() {
        StringBuilder all = new StringBuilder();
        appendFile(all, "wrapper.log");
        appendFile(all, "vproxy.log");
        if (all.length() == 0) {
            return "暂无日志。请先启动服务。";
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
            all.append("读取 ").append(name).append(" 失败: ").append(e.getMessage()).append("\n\n");
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
