package com.fongmi.android.tv.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.RequiresApi;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticEvent;
import com.github.catvod.utils.Prefers;

import java.util.List;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PreviousProcessExitLogger {

    private static final String KEY_LAST_EXIT_TIMESTAMP = "debug_last_process_exit_timestamp";

    private PreviousProcessExitLogger() {
    }

    public static void log(Context context) {
        if (!SpiderDebug.isEnabled()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DebugLogStore.event(event().unknown("exitReason", DiagnosticEvent.Status.NOT_SUPPORTED));
            return;
        }
        Context app = context.getApplicationContext();
        Task.execute(() -> {
            if (!SpiderDebug.isEnabled()) return;
            try { logApi30(app); }
            catch (Throwable error) {
                DebugLogStore.event(event().unknown("exitReason", DiagnosticEvent.Status.READ_ERROR)
                        .observed("javaClass", error.getClass().getSimpleName()));
                SpiderDebug.log("process-exit", "query failed error=%s", error.getClass().getSimpleName());
            }
        });
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void logApi30(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) { DebugLogStore.event(event().unknown("exitReason", DiagnosticEvent.Status.UNAVAILABLE)); return; }
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
        if (exits.isEmpty()) { DebugLogStore.event(event().unknown("exitReason", DiagnosticEvent.Status.UNAVAILABLE)); return; }
        ApplicationExitInfo exit = exits.get(0);
        long timestamp = exit.getTimestamp();
        if (timestamp <= Prefers.getLong(KEY_LAST_EXIT_TIMESTAMP, 0)) return;
        Prefers.put(KEY_LAST_EXIT_TIMESTAMP, timestamp);
        SpiderDebug.log("process-exit",
                "previous reason=%s(%d) status=%d importance=%d timestamp=%d pss=%d rss=%d description=%s",
                reasonName(exit.getReason()), exit.getReason(), exit.getStatus(), exit.getImportance(),
                timestamp, exit.getPss(), exit.getRss(), safe(exit.getDescription()));
        DebugLogStore.event(event().observed("exitReason", reasonName(exit.getReason())).observed("exitStatus", exit.getStatus())
                .observed("firstSeenMs", timestamp).unknown("previousRun", DiagnosticEvent.Status.UNKNOWN)
                .observed("reason", "previous-process-exit; correlate with journal, not current trace").pin("previous-process-exit"));
        captureTrace(exit);
    }

    private static DiagnosticEvent event() {
        return new DiagnosticEvent("process.recovery", "none", "process", 0, 0)
                .source("android", String.valueOf(Build.VERSION.SDK_INT), "app", "ApplicationExitInfo", "previous-process; trace-unresolved",
                        null, null, 0, android.os.SystemClock.elapsedRealtimeNanos());
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void captureTrace(ApplicationExitInfo exit) {
        final int limit = 65536;
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) {
                DebugLogStore.event(event().unknown("traceAvailable", DiagnosticEvent.Status.UNAVAILABLE));
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] part = new byte[4096];
            int read;
            while (bytes.size() < limit && (read = input.read(part, 0, Math.min(part.length, limit - bytes.size()))) > 0) {
                bytes.write(part, 0, read);
            }
            boolean anr = exit.getReason() == ApplicationExitInfo.REASON_ANR;
            boolean nativeCrash = exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE;
            DebugLogStore.event(event().observed("traceAvailable", true).observed("bytes", bytes.size())
                    .observed("format", anr ? "anr-text-prefix"
                            : nativeCrash ? "native-tombstone-protobuf-base64-prefix" : "platform-trace-not-exported")
                    .observed("truncated", bytes.size() == limit));
            if (anr || nativeCrash) {
                String trace = anr ? new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                        : Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                int chunks = (trace.length() + 999) / 1000;
                for (int i = 0; i < chunks; i++) {
                    DebugLogStore.event(event().observed("chunk", i).observed("chunkCount", chunks)
                            .observed("encoding", anr ? "utf-8" : "base64")
                            .message(trace.substring(i * 1000, Math.min(trace.length(), (i + 1) * 1000))));
                }
            }
        } catch (Exception error) {
            DebugLogStore.event(event().unknown("traceAvailable", DiagnosticEvent.Status.READ_ERROR)
                    .observed("javaClass", error.getClass().getSimpleName()));
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String reasonName(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH -> "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR -> "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER -> "OTHER";
            default -> "UNKNOWN";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
