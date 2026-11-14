package com.satlink.geckoviewtest;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** aria2c command line and console parsing for torrent and magnet downloads. Pure Java. */
final class TorrentSupport {
    private TorrentSupport() { }

    static final class Progress {
        long done, total, speed, upload;
        int connections, seeds;
        boolean seeding;
    }

    /** Parses an aria2c readout such as "[#2089b0 400.0KiB/33.2MiB(1%) CN:8 SD:3 DL:115.7KiB ETA:4m51s]". */
    static Progress parse(String line) {
        if (line == null) return null;
        int open = line.indexOf("[#");
        if (open < 0) return null;
        int close = line.indexOf(']', open);
        String body = close < 0 ? line.substring(open + 2) : line.substring(open + 2, close);
        String[] tokens = body.trim().split("\\s+");
        if (tokens.length < 2) return null;
        Progress progress = new Progress();
        String amounts = tokens[1];
        if (amounts.startsWith("SEED")) {
            progress.seeding = true;
        } else {
            int bracket = amounts.indexOf('(');
            if (bracket >= 0) amounts = amounts.substring(0, bracket);
            int slash = amounts.indexOf('/');
            if (slash <= 0) return null;
            progress.done = size(amounts.substring(0, slash));
            progress.total = size(amounts.substring(slash + 1));
            if (progress.done < 0 || progress.total < 0) return null;
        }
        for (int i = 2; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.startsWith("CN:")) progress.connections = integer(token.substring(3));
            else if (token.startsWith("SD:")) progress.seeds = integer(token.substring(3));
            else if (token.startsWith("DL:")) progress.speed = Math.max(0, size(token.substring(3)));
            else if (token.startsWith("UL:")) progress.upload = Math.max(0, size(token.substring(3)));
        }
        return progress;
    }

    /** "FILE: /path/to/name" lines name the file being written. */
    static String filePath(String line) {
        if (line == null) return "";
        String trimmed = line.trim();
        return trimmed.startsWith("FILE: ") ? trimmed.substring(6).trim() : "";
    }

    /** First path element below the download folder: the torrent's file or its folder. */
    static String topLevel(String path, String dir) {
        if (path == null || dir == null || path.isEmpty()) return "";
        String base = dir.endsWith("/") ? dir : dir + "/";
        if (!path.startsWith(base)) return "";
        String rest = path.substring(base.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** "1.2MiB" -> bytes. Returns -1 for text that is not a size. */
    static long size(String text) {
        if (text == null) return -1;
        String value = text.trim();
        long unit = 1;
        if (value.endsWith("KiB")) unit = 1024L;
        else if (value.endsWith("MiB")) unit = 1024L * 1024;
        else if (value.endsWith("GiB")) unit = 1024L * 1024 * 1024;
        else if (value.endsWith("TiB")) unit = 1024L * 1024 * 1024 * 1024;
        else if (!value.endsWith("B")) return -1;
        String number = value.replaceAll("[KMGT]?i?B$", "");
        try { return Math.round(Double.parseDouble(number) * unit); }
        catch (NumberFormatException error) { return -1; }
    }

    private static int integer(String text) {
        try { return Integer.parseInt(text.trim()); } catch (NumberFormatException error) { return 0; }
    }

    static boolean isMagnet(String link) {
        return link != null && link.toLowerCase(Locale.ROOT).startsWith("magnet:?");
    }

    /** Lower-case hex info hash from a magnet link (hex or base32 form), or "". */
    static String infoHash(String magnet) {
        String value = parameter(magnet, "xt");
        String prefix = "urn:btih:";
        if (!value.toLowerCase(Locale.ROOT).startsWith(prefix)) return "";
        String hash = value.substring(prefix.length());
        if (hash.matches("[0-9a-fA-F]{40}")) return hash.toLowerCase(Locale.ROOT);
        if (hash.matches("[A-Za-z2-7]{32}")) return base32ToHex(hash.toUpperCase(Locale.ROOT));
        return "";
    }

    static String displayName(String magnet) {
        String name = parameter(magnet, "dn");
        return name.isEmpty() ? "" : GeneralFilePolicy.safeName(name);
    }

    private static String parameter(String magnet, String key) {
        if (!isMagnet(magnet)) return "";
        String query = magnet.substring(magnet.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || !pair.substring(0, equals).equalsIgnoreCase(key)) continue;
            try { return URLDecoder.decode(pair.substring(equals + 1).replace("+", "%2B"), "UTF-8"); }
            catch (UnsupportedEncodingException | IllegalArgumentException error) { return pair.substring(equals + 1); }
        }
        return "";
    }

    private static String base32ToHex(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder hex = new StringBuilder();
        long buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            while (bits >= 4) {
                bits -= 4;
                hex.append(Character.forDigit((int) ((buffer >> bits) & 15), 16));
            }
        }
        return hex.toString();
    }

    /** aria2c options for one torrent or magnet download. */
    static List<String> arguments(String source, String dir, String dhtFile, String caFile, long appPid) {
        List<String> args = new ArrayList<>();
        args.add("--no-conf=true");
        args.add("--enable-color=false");
        args.add("--summary-interval=1");
        args.add("--console-log-level=warn");
        args.add("--dir=" + dir);
        args.add("--continue=true");
        args.add("--seed-time=0");
        args.add("--bt-save-metadata=true");
        args.add("--follow-torrent=true");
        args.add("--file-allocation=none");
        // c-ares cannot read Android's DNS settings; use the system resolver.
        args.add("--async-dns=false");
        args.add("--enable-dht=true");
        args.add("--dht-file-path=" + dhtFile);
        args.add("--dht-entry-point=dht.transmissionbt.com:6881");
        args.add("--bt-enable-lpd=true");
        args.add("--enable-peer-exchange=true");
        args.add("--bt-max-peers=128");
        args.add("--bt-request-peer-speed-limit=0");
        args.add("--max-connection-per-server=16");
        args.add("--split=32");
        args.add("--min-split-size=1M");
        args.add("--listen-port=6881-6999");
        args.add("--dht-listen-port=6881-6999");
        if (caFile != null && !caFile.isEmpty()) args.add("--ca-certificate=" + caFile);
        if (appPid > 0) args.add("--stop-with-process=" + appPid);
        args.add(source);
        return args;
    }
}
