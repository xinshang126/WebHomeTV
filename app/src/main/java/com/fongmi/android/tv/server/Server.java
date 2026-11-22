package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

public class Server {

    public static final int PORT = 9978;

    private volatile PlaybackService service;
    private volatile Nano nano;
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

    public synchronized void startManage() {
        manage = true;
        start();
    }

    public synchronized void stopManage() {
        manage = false;
        if (service == null) stop();
    }

    public synchronized void start() {
        if (nano != null) return;
        try {
            nano = new Nano(PORT);
            nano.start(500);
            Proxy.set(PORT);
            if (routeRegistration != null) routeRegistration.close();
            routeRegistration = PlaybackRouteRegistry.registerAppService(PORT, PlaybackRouteRegistry.AppOwner.MAIN_SERVER);
            SpiderDebug.log("server", "fixed port ready port=%s", PORT);
        } catch (Throwable e) {
            nano = null;
            SpiderDebug.log("server", "fixed port unavailable port=%s error=%s", PORT, e.getClass().getSimpleName());
        }
    }

    public void stop() {
        Task.execute(() -> {
            synchronized (this) {
                if (manage || service != null) return;
                if (nano != null) nano.stop();
                nano = null;
                if (routeRegistration != null) routeRegistration.close();
                routeRegistration = null;
            }
        });
    }
}
