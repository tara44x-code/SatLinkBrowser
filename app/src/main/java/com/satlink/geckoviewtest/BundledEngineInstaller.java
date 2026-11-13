package com.satlink.geckoviewtest;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Installs the exact SL.Tube engine, without downgrading a newer downloaded engine. */
final class BundledEngineInstaller {
    static final String VERSION = "2026.08.19";
    static final String SHA256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6";
    interface Validator { String version() throws Exception; }

    static boolean install(File target, InputStream bundled, String installedVersion, Validator validator) throws Exception {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create engine directory");
        File staged = new File(parent, "satlink-bundled.stage");
        File backup = new File(parent, "satlink-bundled.backup");
        boolean replaced = false;
        boolean hadActive = target.isFile();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = bundled; FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buffer = new byte[32768]; int count; long bytes = 0;
                while ((count = in.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > 8L * 1024 * 1024) throw new IOException("Bundled engine is too large");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.US, "%02x", value & 255));
            if (!SHA256.equals(hash.toString())) throw new IOException("Bundled engine checksum mismatch");
            if (hadActive && !needsUpgrade(installedVersion)) return false;
            if (hadActive) Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            move(staged, target); replaced = true;
            String actual = validator.version();
            if (!VERSION.equals(actual == null ? "" : actual.trim()))
                throw new IOException("Bundled engine did not return its expected version");
            return true;
        } catch (Exception error) {
            if (replaced) {
                if (hadActive && backup.isFile()) move(backup, target);
                else Files.deleteIfExists(target.toPath());
            }
            throw error;
        } finally {
            Files.deleteIfExists(staged.toPath());
            Files.deleteIfExists(backup.toPath());
        }
    }

    static boolean needsUpgrade(String installed) {
        if (installed == null || !installed.trim().matches("\\d{4}\\.\\d{2}\\.\\d{2}(?:[.\\-+].*)?")) return true;
        return installed.trim().substring(0, 10).compareTo(VERSION) < 0;
    }

    private static void move(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
}
