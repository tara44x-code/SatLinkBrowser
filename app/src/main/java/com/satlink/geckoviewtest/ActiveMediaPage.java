package com.satlink.geckoviewtest;

import java.net.URI;
import java.util.Locale;

/** Keeps the browser document URL separate from the current feed video's post URL. */
final class ActiveMediaPage {
    static String validTikTokPost(String value) {
        try {
            URI u=new URI(value);
            if (web(u) && tikTok(u.getHost()) && u.getPath().matches("/@[^/]+/video/\\d+/?")) return value;
        } catch (Exception ignored) { }
        return "";
    }

    static String resolve(String page, String activePost) {
        if (page == null) return "";
        try {
            URI u=new URI(page);
            if (!tikTok(u.getHost())) return page;
            String direct=validTikTokPost(page);
            if (!direct.isEmpty()) return direct;
            String active=validTikTokPost(activePost);
            if (!active.isEmpty()) return active;
            String host=u.getHost().toLowerCase(Locale.ROOT);
            if (web(u) && (host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com"))
                    && u.getPath()!=null && u.getPath().length()>1) return page;
            return ""; // The root/profile/feed page is not the currently playing post.
        } catch (Exception ignored) {return page;}
    }

    private static boolean web(URI u) {
        return u.getRawUserInfo()==null && ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()));
    }

    private static boolean tikTok(String host) {
        if(host==null) return false;
        String h=host.toLowerCase(Locale.ROOT);
        return h.equals("tiktok.com") || h.endsWith(".tiktok.com");
    }
}
