package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.fongmi.android.tv.server.process.DebugLogs;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Server {

    public static final int DEBUG_PORT = 9977;

    private volatile PlaybackService service;
    private volatile Nano nano;
    private volatile NanoHTTPD debugNano;
    private volatile boolean manage;
    private PlaybackRouteRegistry.Registration routeRegistration;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public void setService(PlaybackService service) {
        this.service = service;
    }

    public boolean isRunning() {
        return nano != null;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + Proxy.getPort();
    }

    public String getLogAddress(boolean local) {
        String base = debugNano == null
                ? getAddress(local)
                : "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + DEBUG_PORT;
        return base + "/debug/logs";
    }

    public synchronized void startManage() {
        manage = true;
        start();
    }

    public synchronized void stopManage() {
        manage = false;
        if (service == null) stop();
    }

    public synchronized void start() {
        if (nano == null) {
            for (int port = 9978; port < 9999; port++) {
                try {
                    nano = new Nano(port);
                    nano.start(500);
                    Proxy.set(port);
                    if (routeRegistration != null) routeRegistration.close();
                    routeRegistration = PlaybackRouteRegistry.registerAppService(port, PlaybackRouteRegistry.AppOwner.MAIN_SERVER);
                    break;
                } catch (Throwable e) {
                    nano = null;
                }
            }
        }
        startDebug();
    }

    private void startDebug() {
        if (debugNano != null) return;
        NanoHTTPD candidate = new NanoHTTPD(DEBUG_PORT) {
            private final DebugLogs logs = new DebugLogs();

            @Override
            public Response serve(IHTTPSession session) {
                String url = session.getUri().trim();
                if (!logs.isRequest(session, url)) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
                }
                Map<String, String> files = new HashMap<>();
                if (session.getMethod() == Method.POST) {
                    try { session.parseBody(files); } catch (Exception ignored) { }
                }
                return logs.doResponse(session, url, files);
            }
        };
        try {
            candidate.start(500);
            debugNano = candidate;
            SpiderDebug.log("server", "fixed debug port ready port=%s", DEBUG_PORT);
        } catch (Throwable e) {
            try { candidate.stop(); } catch (Throwable ignored) { }
            SpiderDebug.log("server", "fixed debug port unavailable port=%s error=%s", DEBUG_PORT, e.getClass().getSimpleName());
        }
    }

    public void stop() {
        Task.execute(() -> {
            synchronized (this) {
                if (manage || service != null) return;
                if (nano != null) nano.stop();
                nano = null;
                if (debugNano != null) debugNano.stop();
                debugNano = null;
                if (routeRegistration != null) routeRegistration.close();
                routeRegistration = null;
            }
        });
    }
}
