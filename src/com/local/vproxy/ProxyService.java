package com.local.vproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
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
    private static final String CHANNEL_ID = "vertex_proxy_alive";
    private static final String PREFS = "vproxy_settings";
    static final String ACTION_WATCHDOG = "com.local.vproxy.WATCHDOG";
    private static final String PREF_SERVICE_ENABLED = "service_enabled";
    private static final long WATCHDOG_INTERVAL_MS = 60000;
    private static final long WATCHDOG_REFRESH_INTERVAL_MS = 30000;
    private static final long KEEPALIVE_SLEEP_MS = 2000;
    private static final long LOCAL_PROBE_INTERVAL_MS = 30000;
    private static final long LOG_TOUCH_INTERVAL_MS = 15000;
    private static final long STARTUP_GRACE_MS = 8000;
    private static final int WATCHDOG_REQUEST_CODE = 2156;
    private Process process;
    private File wrapperLog;
    private volatile boolean keepAliveRunning;
    private volatile long lastNativeStartAt;
    private volatile boolean audioKeepAliveRunning;
    private Thread audioKeepAliveThread;
    private PowerManager.WakeLock wakeLock;

    static void startServiceIfEnabled(Context context, String reason) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String password = prefs.getString("admin_password", "");
        if (password == null || password.trim().isEmpty()) {
            return;
        }
        if (!prefs.getBoolean(PREF_SERVICE_ENABLED, true)) {
            return;
        }
        Intent service = new Intent(context, ProxyService.class);
        service.putExtra("start_reason", reason);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            appendStaticWrapperLog(context, "startServiceIfEnabled failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static void appendStaticWrapperLog(Context context, String message) {
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), "wrapper.log"), true)) {
            writer.write(System.currentTimeMillis() + " " + message + "\n");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        acquireWakeLock();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(1, notification());
        }
        scheduleWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String password = intent.getStringExtra("admin_password");
            boolean enableService = intent.getBooleanExtra(PREF_SERVICE_ENABLED, true);
            if (password != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString("admin_password", password)
                    .putBoolean(PREF_SERVICE_ENABLED, enableService)
                    .apply();
            } else if (enableService) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_SERVICE_ENABLED, true)
                    .apply();
            }
            appendWrapperLog("onStartCommand reason=" + intent.getStringExtra("start_reason") + " action=" + intent.getAction());
        }
        if (!shouldRunInBackground()) {
            appendWrapperLog("service disabled by user; stopping");
            cancelWatchdog();
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        startAudioKeepAlive();
        startProxyIfMissing();
        startKeepAliveLoop();
        scheduleWatchdog();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        keepAliveRunning = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        stopAudioKeepAlive();
        releaseWakeLock();
        if (shouldRunInBackground()) {
            scheduleWatchdog();
        } else {
            cancelWatchdog();
        }
        super.onDestroy();
    }

    private synchronized void startAudioKeepAlive() {
        if (audioKeepAliveRunning) {
            return;
        }
        audioKeepAliveRunning = true;
        audioKeepAliveThread = new Thread(() -> {
            AudioTrack track = null;
            try {
                int sampleRate = 8000;
                int minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                );
                int bufferSize = Math.max(minBuffer, sampleRate);
                track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                );
                byte[] silence = new byte[bufferSize];
                track.play();
                appendWrapperLog("audio keepalive started");
                while (audioKeepAliveRunning && shouldRunInBackground()) {
                    int written = track.write(silence, 0, silence.length);
                    if (written <= 0) {
                        Thread.sleep(200);
                    }
                }
            } catch (Exception e) {
                appendWrapperLog("audio keepalive failed: " + e.getClass().getName() + ": " + e.getMessage());
            } finally {
                if (track != null) {
                    try {
                        track.pause();
                        track.flush();
                        track.release();
                    } catch (Exception ignored) {
                    }
                }
                audioKeepAliveRunning = false;
                appendWrapperLog("audio keepalive stopped");
            }
        }, "vproxy-audio-keepalive");
        audioKeepAliveThread.start();
    }

    private synchronized void stopAudioKeepAlive() {
        audioKeepAliveRunning = false;
        if (audioKeepAliveThread != null) {
            audioKeepAliveThread.interrupt();
            audioKeepAliveThread = null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        appendWrapperLog("task removed; keeping foreground service alive");
        startAudioKeepAlive();
        scheduleWatchdog();
        startKeepAliveLoop();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startProxyIfMissing() {
        if (process != null) {
            return;
        }
        startProxy();
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
            lastNativeStartAt = System.currentTimeMillis();
            appendWrapperLog("native process started");
            new Thread(() -> {
                try {
                    int code = started.waitFor();
                    appendWrapperLog("native process exited code=" + code);
                    synchronized (ProxyService.this) {
                        if (process == started) {
                            process = null;
                            lastNativeStartAt = 0;
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

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VertexProxy:AlwaysOn");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            appendWrapperLog("wakelock acquire failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean shouldRunInBackground() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String password = prefs.getString("admin_password", "");
        return password != null && password.trim().length() > 0 &&
            prefs.getBoolean(PREF_SERVICE_ENABLED, true);
    }

    private void scheduleWatchdog() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            PendingIntent pendingIntent = watchdogPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT);
            long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                }
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (Exception e) {
            appendWrapperLog("watchdog schedule failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void cancelWatchdog() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pendingIntent = watchdogPendingIntent(PendingIntent.FLAG_NO_CREATE);
            if (alarmManager != null && pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
            }
        } catch (Exception ignored) {
        }
    }

    private PendingIntent watchdogPendingIntent(int flags) {
        Intent intent = new Intent(this, WatchdogReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, WATCHDOG_REQUEST_CODE, intent, flags);
    }

    private synchronized void startKeepAliveLoop() {
        if (keepAliveRunning) {
            return;
        }
        keepAliveRunning = true;
        new Thread(() -> {
            int tick = 0;
            long lastProbeAt = 0;
            long lastLogReadAt = 0;
            long lastWatchdogAt = 0;
            int failedProbeCount = 0;
            appendWrapperLog("keepalive loop started");
            while (keepAliveRunning) {
                try {
                    long now = System.currentTimeMillis();
                    boolean probed = false;
                    boolean localProbeOk = true;
                    if (!shouldRunInBackground()) {
                        appendWrapperLog("keepalive saw disabled service; stopping self");
                        stopSelf();
                        break;
                    }
                    acquireWakeLock();
                    startAudioKeepAlive();
                    if (now - lastWatchdogAt >= WATCHDOG_REFRESH_INTERVAL_MS) {
                        scheduleWatchdog();
                        lastWatchdogAt = now;
                    }
                    if (now - lastLogReadAt >= LOG_TOUCH_INTERVAL_MS) {
                        readLogsSilently();
                        lastLogReadAt = now;
                    }
                    if (now - lastProbeAt >= LOCAL_PROBE_INTERVAL_MS) {
                        localProbeOk = isLocalServerHealthy();
                        lastProbeAt = now;
                        probed = true;
                    }
                    boolean shouldRestart;
                    synchronized (ProxyService.this) {
                        shouldRestart = process == null;
                    }
                    if (shouldRestart && keepAliveRunning) {
                        appendWrapperLog("keepalive detected stopped native process; restarting");
                        startProxy();
                    } else if (keepAliveRunning && probed) {
                        synchronized (ProxyService.this) {
                            long sinceStart = System.currentTimeMillis() - lastNativeStartAt;
                            if (process != null && sinceStart >= STARTUP_GRACE_MS && !localProbeOk) {
                                failedProbeCount++;
                                appendWrapperLog("keepalive local probe failed count=" + failedProbeCount);
                                if (failedProbeCount >= 2) {
                                    appendWrapperLog("keepalive local probe failed twice; restarting");
                                    failedProbeCount = 0;
                                    startProxy();
                                }
                            } else {
                                failedProbeCount = 0;
                            }
                        }
                    }
                    tick++;
                    Thread.sleep(KEEPALIVE_SLEEP_MS);
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
            keepAliveRunning = false;
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
        httpProbe("http://127.0.0.1:2156/");
    }

    private boolean isLocalServerHealthy() {
        return httpProbe("http://127.0.0.1:2156/");
    }

    private boolean httpProbe(String urlText) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[256];
                while (in.read(buffer) != -1) {
                    break;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
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
                "  \"parallel_pool_enabled\": true,\n" +
                "  \"parallel_pool_size\": 4,\n" +
                "  \"force_no_stream\": true,\n" +
                "  \"android_stream_mode_migrated\": true,\n" +
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
            boolean changed = false;
            if (text.contains("\"proxy_url\"") && text.contains("127.0.0.1:1080")) {
                text = Pattern.compile("\"proxy_url\"\\s*:\\s*\"socks5://127\\.0\\.0\\.1:1080\"")
                    .matcher(text)
                    .replaceAll("\"proxy_url\": \"\"");
                appendWrapperLog("migrated default 127.0.0.1:1080 proxy to empty proxy");
                changed = true;
            }
            if (text.contains("\"parallel_pool_enabled\"") &&
                Pattern.compile("\"parallel_pool_enabled\"\\s*:\\s*false").matcher(text).find() &&
                !Pattern.compile("\"active_node_uri\"\\s*:\\s*\"[^\"]+\"").matcher(text).find() &&
                !Pattern.compile("\"proxy_url\"\\s*:\\s*\"[^\"]+\"").matcher(text).find()) {
                text = Pattern.compile("\"parallel_pool_enabled\"\\s*:\\s*false")
                    .matcher(text)
                    .replaceAll("\"parallel_pool_enabled\": true");
                appendWrapperLog("enabled parallel node pool because no active node is locked");
                changed = true;
            }
            if (!text.contains("\"parallel_pool_size\"")) {
                text = text.replaceFirst("\\n\\}", ",\\n  \"parallel_pool_size\": 4\\n}");
                appendWrapperLog("added default parallel_pool_size=4");
                changed = true;
            }
            if (!text.contains("\"android_stream_mode_migrated\"")) {
                if (Pattern.compile("\"force_no_stream\"\\s*:\\s*false").matcher(text).find()) {
                    text = Pattern.compile("\"force_no_stream\"\\s*:\\s*false")
                        .matcher(text)
                        .replaceAll("\"force_no_stream\": true");
                    appendWrapperLog("enabled force_no_stream for stable Android agent streaming");
                } else if (!text.contains("\"force_no_stream\"")) {
                    text = text.replaceFirst("\\n\\}", ",\\n  \"force_no_stream\": true\\n}");
                    appendWrapperLog("added force_no_stream=true for stable Android agent streaming");
                }
                text = text.replaceFirst("\\n\\}", ",\\n  \"android_stream_mode_migrated\": true\\n}");
                changed = true;
            }
            if (changed) {
                Files.write(configFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            appendWrapperLog("config migration skipped: " + e.getClass().getName() + ": " + e.getMessage());
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
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Vertex Proxy 正在后台运行")
                .setContentText("本地端点 http://127.0.0.1:2156/v1")
                .setContentIntent(pendingIntent)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            }
            return builder.build();
        }
        return new Notification.Builder(this)
            .setContentTitle("Vertex Proxy 正在后台运行")
            .setContentText("本地端点 http://127.0.0.1:2156/v1")
            .setContentIntent(pendingIntent)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }
}
