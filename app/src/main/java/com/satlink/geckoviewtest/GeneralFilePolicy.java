package com.satlink.geckoviewtest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** General file metadata only. Never classifies video/audio links as file navigation. */
final class GeneralFilePolicy {
    private static final Map<String,String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("zip", "application/zip");
        TYPES.put("rar", "application/vnd.rar");
        TYPES.put("7z", "application/x-7z-compressed");
        TYPES.put("gz", "application/gzip");
        TYPES.put("tgz", "application/gzip");
        TYPES.put("tar", "application/x-tar");
        TYPES.put("bz2", "application/x-bzip2");
        TYPES.put("xz", "application/x-xz");
        TYPES.put("zst", "application/zstd");
        TYPES.put("apk", "application/vnd.android.package-archive");
        TYPES.put("torrent", "application/x-bittorrent");
        TYPES.put("exe", "application/vnd.microsoft.portable-executable");
        TYPES.put("msi", "application/x-msi");
        TYPES.put("pdf", "application/pdf");
        TYPES.put("doc", "application/msword");
        TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        TYPES.put("xls", "application/vnd.ms-excel");
        TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        TYPES.put("ppt", "application/vnd.ms-powerpoint");
        TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        for (String ext : new String[]{"iso","img","cab","msix","msixbundle","appx","appxbundle",
                "apks","xapk","aab","dmg","pkg","deb","rpm","epub","mobi","bin",
                // More archives, disc images, installers, game files, documents, fonts and subtitles.
                "apkm","obb","ipa","jar","ace","arj","lzh","lz","lz4","lzma","cbz","cbr","001","r00",
                "nrg","mdf","mds","cue","vhd","vhdx","vmdk","qcow2","ova","wim","esd",
                "chd","cso","wbfs","nsp","xci","gba","nds","3ds","n64","z64","sfc","smc",
                "odt","ods","odp","rtf","azw3","djvu","ttf","otf","srt","ass","ssa","sub","psd"})
            TYPES.put(ext, "application/octet-stream");
    }

    static boolean http(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost()!=null && uri.getRawUserInfo()==null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) {return false;}
    }

    static boolean directFile(String url) {
        return http(url) && !media(url,"","") && !extension(urlName(url)).equals("bin") && TYPES.containsKey(extension(urlName(url)));
    }

    static boolean media(String url, String mime, String disposition) {
        String type = cleanMime(mime);
        if (type.startsWith("video/") || type.startsWith("audio/")
                || type.contains("mpegurl") || type.contains("dash+xml")
                || type.contains("yt-ump") || type.contains("sabr")) return true;
        try {
            URI uri=new URI(url);
            String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
            if(host.equals("googlevideo.com") || host.endsWith(".googlevideo.com")) return true;
            String query=uri.getRawQuery();
            if(query!=null) for(String param:query.split("&")) {
                String[] pair=param.split("=",2);
                if(pair.length==2 && (pair[0].equals("mime") || pair[0].equals("mime_type"))) {
                    String value=decode(pair[1],"UTF-8").replace('_','/');
                    if(value.startsWith("video/") || value.startsWith("audio/")) return true;
                }
            }
        } catch(Exception ignored) { }
        String ext = extension(urlName(url));
        String named = extension(dispositionName(disposition));
        return mediaExtension(ext) || mediaExtension(named);
    }

    private static boolean mediaExtension(String ext) {
        return ext.matches("mp4|webm|mkv|mov|m4v|avi|flv|wmv|mpg|mpeg|ts|m4s|m3u8|mpd|mp3|m4a|aac|ogg|opus|wav|flac");
    }

    static String mime(String name, String supplied) {
        String type = cleanMime(supplied);
        if (!type.isEmpty() && !type.equals("application/octet-stream")) return type;
        return TYPES.getOrDefault(extension(name), "application/octet-stream");
    }

    static String cleanMime(String value) {
        if (value==null) return "";
        return value.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static String fileName(String url, String disposition, String mime) {
        String name = dispositionName(disposition);
        if (name.isEmpty()) name = urlName(url);
        if (name.isEmpty()) {
            String type = cleanMime(mime);
            String ext = "bin";
            for (Map.Entry<String,String> entry:TYPES.entrySet()) {
                if (!type.equals("application/octet-stream") && type.equals(entry.getValue())) {ext=entry.getKey();break;}
            }
            name="download."+ext;
        }
        return safeName(name);
    }

    static String safeName(String value) {
        String clean = value==null?"":value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        clean=clean.replaceAll("[. ]+$", "");
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean="download.bin";
        // Bound UTF-8 bytes, not Java characters; preserve the filename extension.
        if (clean.getBytes(StandardCharsets.UTF_8).length>180) {
            int dot=clean.lastIndexOf('.');
            String suffix=dot>0 && clean.length()-dot<20?clean.substring(dot):"";
            String stem=suffix.isEmpty()?clean:clean.substring(0,dot);
            while (!stem.isEmpty() && (stem+suffix).getBytes(StandardCharsets.UTF_8).length>180)
                stem=stem.substring(0,stem.offsetByCodePoints(stem.length(),-1));
            clean=stem+suffix;
        }
        return clean;
    }

    private static String extension(String name) {
        int dot=name==null?-1:name.lastIndexOf('.');
        return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }

    private static String urlName(String value) {
        try {
            String path=new URI(value).getRawPath();
            if (path==null || path.isEmpty() || path.endsWith("/")) return "";
            return decode(path.substring(path.lastIndexOf('/')+1),"UTF-8");
        } catch (Exception ignored) {return "";}
    }

    private static String dispositionName(String header) {
        if (header==null) return "";
        Matcher encoded=Pattern.compile("(?:^|;)\\s*filename\\*\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))",Pattern.CASE_INSENSITIVE).matcher(header);
        if (encoded.find()) {
            String value=(encoded.group(1)!=null?encoded.group(1):encoded.group(2)).trim();
            int first=value.indexOf('\'');int second=first<0?-1:value.indexOf('\'',first+1);
            if (first>0 && second>=0) {
                String charset=value.substring(0,first);
                if (charset.equalsIgnoreCase("UTF-8") || charset.equalsIgnoreCase("ISO-8859-1"))
                    return decode(value.substring(second+1),charset);
            }
        }
        Matcher plain=Pattern.compile("(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))",Pattern.CASE_INSENSITIVE).matcher(header);
        if (!plain.find()) return "";
        return (plain.group(1)!=null?plain.group(1).replace("\\\"","\"").replace("\\\\","\\"):plain.group(2)).trim();
    }

    private static String decode(String text, String charset) {
        try {return URLDecoder.decode(text.replace("+","%2B"),charset);} catch (Exception ignored) {return text;}
    }

    static String cookieHeader(String netscape, String url, long nowSeconds) {
        if (!http(url) || netscape==null) return "";
        try {
            URI target=new URI(url);String host=target.getHost().toLowerCase(Locale.ROOT);
            String path=target.getPath();if(path==null || path.isEmpty()) path="/";
            List<String[]> matched=new ArrayList<>();
            for(String line:netscape.split("\\r?\\n")) {
                if(line.startsWith("#HttpOnly_")) line=line.substring(10);
                else if(line.startsWith("#")) continue;
                String[] row=line.split("\\t",7);if(row.length!=7) continue;
                String domain=row[0].replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
                if(!host.equals(domain) && !(row[1].equalsIgnoreCase("TRUE") && host.endsWith("."+domain))) continue;
                String cookiePath=row[2].isEmpty()?"/":row[2];
                if(!path.startsWith(cookiePath) || (!cookiePath.endsWith("/") && path.length()>cookiePath.length() && path.charAt(cookiePath.length())!='/')) continue;
                if(row[3].equalsIgnoreCase("TRUE") && !target.getScheme().equalsIgnoreCase("https")) continue;
                long expiry;try{expiry=Long.parseLong(row[4]);}catch(Exception ignored){continue;}
                if(expiry>0 && expiry<=nowSeconds || row[5].isEmpty() || row[5].matches(".*[\\r\\n;=].*") || row[6].matches(".*[\\r\\n;].*")) continue;
                matched.add(row);
            }
            matched.sort(Comparator.comparingInt((String[] row)->row[2].length()).reversed());
            StringBuilder result=new StringBuilder();
            for(String[] row:matched) {if(result.length()>0) result.append("; ");result.append(row[5]).append('=').append(row[6]);}
            return result.toString();
        } catch(Exception ignored) {return "";}
    }
}
