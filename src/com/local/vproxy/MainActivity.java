package com.local.vproxy;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    private static final String ADMIN_URL = "http://127.0.0.1:2156/admin/";
    private static final String BASE_URL = "http://127.0.0.1:2156/v1";
    private static final String PREFS = "vproxy_settings";

    private EditText adminPasswordInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPassword = prefs.getString("admin_password", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Vertex Proxy");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(
            "\u5148\u8bbe\u7f6e\u7ba1\u7406\u5458\u5bc6\u7801\uff0c\u518d\u542f\u52a8\u672c\u5730\u670d\u52a1\u3002\n\n" +
            "API Key \u8bf7\u5728\u6d4f\u89c8\u5668\u7ba1\u7406\u540e\u53f0\u7684\u300c\u5bc6\u94a5\u300d\u9875\u9762\u6dfb\u52a0\uff0cAPK \u4e0d\u5185\u7f6e\u9ed8\u8ba4 Key\u3002\n\n" +
            "\u4ee3\u7406\u3001\u5bc6\u94a5\u3001\u6a21\u578b\u548c\u8fd0\u884c\u8bbe\u7f6e\u90fd\u8bf7\u5728\u6d4f\u89c8\u5668\u7ba1\u7406\u540e\u53f0\u4fee\u6539\uff0cAPK \u542f\u52a8\u65f6\u4e0d\u8986\u76d6\u5df2\u6709\u540e\u53f0\u914d\u7f6e\u3002\n\n" +
            "\u6a21\u578b\u5df2\u9884\u914d\u7f6e\uff0c\u4e0d\u8bbe\u7f6e\u522b\u540d\u3002"
        );
        hint.setTextSize(15);
        hint.setTextIsSelectable(true);
        root.addView(hint);

        adminPasswordInput = new EditText(this);
        adminPasswordInput.setHint("\u7ba1\u7406\u5458\u5bc6\u7801");
        adminPasswordInput.setSingleLine(true);
        adminPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        adminPasswordInput.setText(savedPassword);
        root.addView(adminPasswordInput);

        Button saveStart = new Button(this);
        saveStart.setText("\u4fdd\u5b58\u5e76\u542f\u52a8\u670d\u52a1");
        saveStart.setOnClickListener(v -> saveAndStart());
        root.addView(saveStart);

        Button openAdmin = new Button(this);
        openAdmin.setText("\u6253\u5f00\u7ba1\u7406\u540e\u53f0");
        openAdmin.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ADMIN_URL))));
        root.addView(openAdmin);

        Button check = new Button(this);
        check.setText("\u68c0\u6d4b\u672c\u5730\u7aef\u70b9");
        check.setOnClickListener(v -> checkService());
        root.addView(check);

        Button logs = new Button(this);
        logs.setText("\u67e5\u770b\u6700\u8fd1\u65e5\u5fd7");
        logs.setOnClickListener(v -> showLogs());
        root.addView(logs);

        Button stop = new Button(this);
        stop.setText("\u505c\u6b62\u670d\u52a1");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ProxyService.class));
            status.setText("\u670d\u52a1\u5df2\u505c\u6b62\u3002");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText(
            "Base URL: " + BASE_URL + "\n" +
            "Model: gemini-2.5-flash\n" +
            "API Key: \u8bf7\u5728\u7ba1\u7406\u540e\u53f0\u6dfb\u52a0\n" +
            "\u4ee3\u7406: \u8bf7\u5728\u7ba1\u7406\u540e\u53f0\u7684\u8bbe\u7f6e\u9875\u4fee\u6539"
        );
        status.setTextSize(15);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAndStart() {
        String password = adminPasswordInput.getText().toString().trim();
        if (password.length() == 0) {
            status.setText("\u7ba1\u7406\u5458\u5bc6\u7801\u4e0d\u80fd\u4e3a\u7a7a\u3002");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("admin_password", password)
            .apply();

        Intent intent = new Intent(this, ProxyService.class);
        intent.putExtra("admin_password", password);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        status.setText(
            "\u5df2\u53d1\u9001\u542f\u52a8\u547d\u4ee4\u3002\n\n" +
            "\u7ba1\u7406\u540e\u53f0: " + ADMIN_URL + "\n" +
            "\u7ba1\u7406\u5bc6\u7801: " + password + "\n\n" +
            "Base URL: " + BASE_URL + "\n" +
            "API Key: \u8bf7\u5728\u7ba1\u7406\u540e\u53f0\u7684\u300c\u5bc6\u94a5\u300d\u9875\u9762\u6dfb\u52a0\n" +
            "\u4ee3\u7406: \u8bf7\u5728\u7ba1\u7406\u540e\u53f0\u7684\u8bbe\u7f6e\u9875\u4fee\u6539\n" +
            "Model: gemini-2.5-flash"
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void checkService() {
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            String[] urls = new String[] {
                "http://127.0.0.1:2156/health",
                "http://127.0.0.1:2156/v1",
                "http://127.0.0.1:2156/v1/",
                "http://127.0.0.1:2156/models",
                "http://127.0.0.1:2156/v1/models",
                "http://127.0.0.1:2156/v1/models/",
                "http://127.0.0.1:2156/v1beta/models",
                "http://127.0.0.1:2156/v1beta/models/"
            };
            for (String url : urls) {
                result.append("=== ").append(url).append(" ===\n");
                try {
                    result.append(httpGet(url, null, 2500, 5000));
                } catch (Exception e) {
                    result.append("\u5931\u8d25: ")
                        .append(e.getClass().getSimpleName())
                        .append(": ")
                        .append(e.getMessage())
                        .append("\n");
                }
                result.append("\n");
            }
            runOnUiThread(() -> status.setText(result.toString()));
        }).start();
    }

    private void showLogs() {
        try {
            File wrapper = new File(getFilesDir(), "wrapper.log");
            File nativeLog = new File(getFilesDir(), "vproxy.log");
            if (!wrapper.exists() && !nativeLog.exists()) {
                status.setText("\u6682\u65e0\u65e5\u5fd7\u6587\u4ef6\uff0c\u8bf7\u5148\u542f\u52a8\u670d\u52a1\u3002");
                return;
            }
            StringBuilder all = new StringBuilder();
            if (wrapper.exists()) {
                all.append("=== wrapper.log ===\n");
                all.append(new String(Files.readAllBytes(wrapper.toPath()), StandardCharsets.UTF_8));
                all.append("\n");
            }
            if (nativeLog.exists()) {
                all.append("=== vproxy.log ===\n");
                all.append(new String(Files.readAllBytes(nativeLog.toPath()), StandardCharsets.UTF_8));
            }
            String text = all.toString();
            int start = Math.max(0, text.length() - 8000);
            status.setText(text.substring(start));
        } catch (Exception e) {
            status.setText("\u8bfb\u53d6\u65e5\u5fd7\u5931\u8d25: " + e.getMessage());
        }
    }

    private String httpGet(String url, String apiKey, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        if (apiKey != null && apiKey.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            code >= 400 ? conn.getErrorStream() : conn.getInputStream()
        ));
        StringBuilder out = new StringBuilder("HTTP " + code + "\n");
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
