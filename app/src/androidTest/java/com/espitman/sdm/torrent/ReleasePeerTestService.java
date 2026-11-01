package com.espitman.sdm.torrent;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.FileOutputStream;

/** Independent peer shipped only in the test APK. Loads the installed engine in its own process. */
public final class ReleasePeerTestService extends Service {
    private volatile boolean stopped;
    private Thread worker;
    private Object session;
    private ResultReceiver receiver;
    private File directory;
    private ClassLoader engineLoader;
    private Class<?> engine(String name) throws ClassNotFoundException {
        return engineLoader.loadClass("com.frostwire.jlibtorrent." + name);
    }
    private void payload(File file, int size, int factor, int modulus) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte)(i * factor % modulus);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    @Override public IBinder onBind(Intent intent) {
        receiver = intent.getParcelableExtra("receiver");
        byte[] metadata = intent.getByteArrayExtra("metadata");
        int port = intent.getIntExtra("port", 0);
        if (receiver == null || metadata == null || metadata.length > 8192 || port < 1024 || port > 65535)
            throw new IllegalArgumentException("Invalid peer fixture");
        worker = new Thread(() -> {
            try {
                android.content.pm.ApplicationInfo app = getPackageManager().getApplicationInfo("com.espitman.sdm", 0);
                engineLoader = new PathClassLoader(app.sourceDir, app.nativeLibraryDir, getClassLoader());
                directory = new File(getCacheDir(), "peer-" + java.util.UUID.randomUUID());
                File bundle = new File(directory, "QA-bundle");
                if (!bundle.mkdirs()) throw new IllegalStateException("Could not create owned fixture");
                payload(new File(bundle, "first.bin"), 2 * 1024 * 1024, 31, 251);
                payload(new File(bundle, "second.bin"), 1024 * 1024, 17, 239);
                Class<?> settingsClass = engine("SettingsPack");
                Object settings = settingsClass.getConstructor().newInstance();
                settingsClass.getMethod("setPeerFingerprint", byte[].class).invoke(settings, (Object)("-QA0001-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                settingsClass.getMethod("listenInterfaces", String.class).invoke(settings, "127.0.0.1:" + port);
                settingsClass.getMethod("uploadRateLimit", int.class).invoke(settings, 128 * 1024);
                // Metadata resolution, cancellation and resumed transfers all share loopback IP.
                Class<?> bools = engineLoader.loadClass("com.frostwire.jlibtorrent.swig.settings_pack$bool_types");
                Object multiple = bools.getField("allow_multiple_connections_per_ip").get(null);
                int multipleId = (Integer)bools.getMethod("swigValue").invoke(multiple);
                settingsClass.getMethod("setBoolean", int.class, boolean.class).invoke(settings, multipleId, true);
                // The fixture serves inbound clients only; stale discovery of the
                // cancelled client's port must not compete with its replacement.
                for (String name : new String[]{"enable_outgoing_tcp", "enable_outgoing_utp", "enable_lsd", "enable_dht"}) {
                    Object option = bools.getField(name).get(null);
                    int optionId = (Integer)bools.getMethod("swigValue").invoke(option);
                    settingsClass.getMethod("setBoolean", int.class, boolean.class).invoke(settings, optionId, false);
                }
                Class<?> paramsClass = engine("SessionParams");
                Object params = paramsClass.getConstructor(settingsClass).newInstance(settings);
                Class<?> managerClass = engine("SessionManager");
                session = managerClass.getConstructor().newInstance();
                managerClass.getMethod("start", paramsClass).invoke(session, params);
                managerClass.getMethod("stopDht").invoke(session);
                Class<?> infoClass = engine("TorrentInfo");
                Object info = infoClass.getConstructor(byte[].class).newInstance((Object)metadata);
                managerClass.getMethod("download", infoClass, File.class).invoke(session, info, directory);
                long deadline = System.currentTimeMillis() + 30_000;
                while (!stopped && System.currentTimeMillis() < deadline) {
                    Object handle = managerClass.getMethod("find", infoClass).invoke(session, info);
                    if (handle != null) {
                        Object status = handle.getClass().getMethod("status").invoke(handle);
                        if ((Boolean)status.getClass().getMethod("isSeeding").invoke(status)) {
                            receiver.send(1, null); return;
                        }
                    }
                    Thread.sleep(100);
                }
                if (!stopped) throw new IllegalStateException("Peer did not reach seeding");
            } catch (Exception error) {
                if (!stopped) {
                    android.os.Bundle data = new android.os.Bundle();
                    data.putString("error", error.toString() + " " + error.getCause());
                    receiver.send(2, data);
                }
            }
        }, "release-independent-peer");
        worker.start();
        return new Binder();
    }
    private void remove(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    @Override public void onDestroy() {
        stopped = true;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try { if (session != null) session.getClass().getMethod("stop").invoke(session); }
        catch (Exception ignored) { }
        remove(directory);
        if (receiver != null) receiver.send(3, null);
        super.onDestroy();
    }
}
