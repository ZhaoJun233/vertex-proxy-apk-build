package com.local.vproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

public class ProxyService extends Service {
    private static final String CHANNEL_ID = "vertex_proxy";
    private static final String PREFS = "vproxy_settings";
    private Process process;
    private File wrapperLog;
    private volatile boolean keepAliveRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String password = intent.getStringExtra("admin_password");
            if (password != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString("admin_password", password)
                    .apply();
            }
        }
        startProxy();
        startKeepAliveLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        keepAliveRunning = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startProxy() {
        File workDir = getFilesDir();
        wrapperLog = new File(workDir, "wrapper.log");
        try {
            appendWrapperLog("start requested");
            if (process != null) {
                appendWrapperLog("destroying previous process object");
                process.destroy();
                process = null;
            }
            writeRuntimeConfig(workDir);
            writeModelsConfig(workDir);
            removeBundledNodes(workDir);

            File binary = new File(getApplicationInfo().nativeLibraryDir, "libvproxy.so");
            File logFile = new File(workDir, "vproxy.log");
            appendWrapperLog("binary=" + binary.getAbsolutePath() + " exists=" + binary.exists() + " canExecute=" + binary.canExecute());
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath());
            builder.directory(workDir);
            File configDir = new File(workDir, "config");
            builder.environment().put("VPROXY_CONFIG_DIR", configDir.getAbsolutePath());
            builder.environment().put("VPROXY_CONFIG", new File(configDir, "config.json").getAbsolutePath());
            builder.environment().put("VPROXY_MODELS", new File(configDir, "models.json").getAbsolutePath());
            builder.environment().put("VPROXY_API_KEYS", new File(configDir, "api_keys.txt").getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile);
            process = builder.start();
            final Process started = process;
            appendWrapperLog("native process started");
            new Thread(() -> {
                try {
                    int code = started.waitFor();
                    appendWrapperLog("native process exited code=" + code);
                    synchronized (ProxyService.this) {
                        if (process == started) {
                            process = null;
                        }
                    }
                } catch (Exception e) {
                    appendWrapperLog("waitFor failed: " + e.getClass().getName() + ": " + e.getMessage());
                }
            }, "vproxy-waiter").start();
        } catch (Exception e) {
            appendWrapperLog("start failed: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private synchronized void appendWrapperLog(String message) {
        try {
            if (wrapperLog == null) {
                wrapperLog = new File(getFilesDir(), "wrapper.log");
            }
            try (FileWriter writer = new FileWriter(wrapperLog, true)) {
                writer.write(System.currentTimeMillis() + " " + message + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void startKeepAliveLoop() {
        if (keepAliveRunning) {
            return;
        }
        keepAliveRunning = true;
        new Thread(() -> {
            int tick = 0;
            appendWrapperLog("keepalive loop started");
            while (keepAliveRunning) {
                try {
                    readLogsSilently();
                    if (tick % 3 == 0) {
                        warmLocalEndpoints();
                    }
                    boolean shouldRestart;
                    synchronized (ProxyService.this) {
                        shouldRestart = process == null;
                    }
                    if (shouldRestart && keepAliveRunning) {
                        appendWrapperLog("keepalive detected stopped native process; restarting");
                        startProxy();
                    }
                    tick++;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    appendWrapperLog("keepalive failed: " + e.getClass().getName() + ": " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            appendWrapperLog("keepalive loop stopped");
        }, "vproxy-keepalive").start();
    }

    private void readLogsSilently() {
        File workDir = getFilesDir();
        readFileSilently(new File(workDir, "wrapper.log"));
        readFileSilently(new File(workDir, "vproxy.log"));
    }

    private void readFileSilently(File file) {
        if (!file.exists()) {
            return;
        }
        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            long length = reader.length();
            int bytesToRead = (int) Math.min(4096, length);
            reader.seek(Math.max(0, length - bytesToRead));
            byte[] buffer = new byte[bytesToRead];
            reader.readFully(buffer);
        } catch (Exception ignored) {
        }
    }

    private void warmLocalEndpoints() {
        httpProbe("http://127.0.0.1:2156/health");
        httpProbe("http://127.0.0.1:2156/v1/models");
    }

    private void httpProbe(String urlText) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void writeRuntimeConfig(File workDir) throws IOException {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String password = prefs.getString("admin_password", "");
        if (password == null || password.trim().isEmpty()) {
            throw new IOException("admin password is empty");
        }

        File configDir = new File(workDir, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        File configFile = new File(configDir, "config.json");
        if (configFile.exists()) {
            migrateDefaultProxyConfig(configFile);
            appendWrapperLog("keep existing config.json");
        } else {
            String configJson = "{\n" +
                "  \"port_api\": 2156,\n" +
                "  \"max_retries\": 2,\n" +
                "  \"admin_password\": \"" + escapeJson(password.trim()) + "\",\n" +
                "  \"proxy_url\": \"\",\n" +
                "  \"parallel_pool_enabled\": false,\n" +
                "  \"force_no_stream\": false,\n" +
                "  \"token_pool_size\": 8,\n" +
                "  \"max_n\": 8\n" +
                "}\n";
            Files.write(configFile.toPath(), configJson.getBytes(StandardCharsets.UTF_8));
            appendWrapperLog("created default config.json");
        }

        File keysFile = new File(configDir, "api_keys.txt");
        if (!keysFile.exists()) {
            String keys = "# format: name:api_key:description\n# Add API keys from the web admin panel.\n";
            Files.write(keysFile.toPath(), keys.getBytes(StandardCharsets.UTF_8));
            appendWrapperLog("created empty api_keys.txt");
        } else {
            appendWrapperLog("keep existing api_keys.txt");
        }
    }

    private void migrateDefaultProxyConfig(File configFile) {
        try {
            String text = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("\"proxy_url\"") || !text.contains("127.0.0.1:1080")) {
                return;
            }
            text = Pattern.compile("\"proxy_url\"\\s*:\\s*\"socks5://127\\.0\\.0\\.1:1080\"")
                .matcher(text)
                .replaceAll("\"proxy_url\": \"\"");
            Files.write(configFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
            appendWrapperLog("migrated default 127.0.0.1:1080 proxy to empty proxy");
        } catch (Exception e) {
            appendWrapperLog("config migration skipped: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void removeBundledNodes(File workDir) {
        File configDir = new File(workDir, "config");
        File nodesFile = new File(configDir, "nodes.json");
        if (!nodesFile.exists()) return;
        try {
            String text = new String(Files.readAllBytes(nodesFile.toPath()), StandardCharsets.UTF_8);
            if (!text.contains("XMRth-Google-")) {
                appendWrapperLog("keep existing nodes.json");
                return;
            }
            Files.write(nodesFile.toPath(), "{\"nodes\":[]}\n".getBytes(StandardCharsets.UTF_8));
            appendWrapperLog("removed bundled XMRth-Google nodes");
        } catch (Exception e) {
            appendWrapperLog("bundled node cleanup skipped: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeModelsConfig(File workDir) throws IOException {
        File configDir = new File(workDir, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        File modelsFile = new File(configDir, "models.json");
        if (modelsFile.exists()) {
            appendWrapperLog("keep existing models.json");
            return;
        }
        String modelsJson = "{\n" +
            "  \"models\": [\n" +
            "    \"gemini-3.5-flash\",\n" +
            "    \"gemini-2.5-flash\",\n" +
            "    \"gemini-2.5-pro\",\n" +
            "    \"gemini-2.5-flash-lite\",\n" +
            "    \"gemini-2.5-flash-image\",\n" +
            "    \"gemini-3-flash-preview\",\n" +
            "    \"gemini-3.1-flash-lite\",\n" +
            "    \"gemini-3.1-pro-preview\",\n" +
            "    \"gemini-3.1-flash-image\",\n" +
            "    \"gemini-3-pro-image\",\n" +
            "    \"gemini-3.1-flash-tts-preview\"\n" +
            "  ],\n" +
            "  \"alias_map\": {}\n" +
            "}\n";
        Files.write(modelsFile.toPath(), modelsJson.getBytes(StandardCharsets.UTF_8));
        appendWrapperLog("created default models.json");
    }

    private void copyAsset(String assetName, File dest, boolean overwrite) throws IOException {
        if (dest.exists() && !overwrite) {
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private Notification notification() {
        Intent intent = new Intent(this, LogActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Vertex Proxy",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Vertex Proxy 正在后台运行")
                .setContentText("本地端点 http://127.0.0.1:2156/v1")
                .setContentIntent(pendingIntent)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build();
        }
        return new Notification.Builder(this)
            .setContentTitle("Vertex Proxy 正在后台运行")
            .setContentText("本地端点 http://127.0.0.1:2156/v1")
            .setContentIntent(pendingIntent)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .build();
    }
}
