package com.satlink.geckoviewtest;

import java.util.List;
import java.util.Locale;

/** Scores downloadable media only. Storyboards do not make an extraction successful. */
final class FormatSelectionPolicy {
    private FormatSelectionPolicy() { }

    static final class Format {
        final String id, url, ext, videoCodec, audioCodec;
        final int height;
        Format(String url, String ext, String videoCodec, String audioCodec, int height) {
            this.id="";this.url=url;this.ext=ext;this.videoCodec=videoCodec;this.audioCodec=audioCodec;this.height=height;
        }
        Format(String id,String url,String ext,String videoCodec,String audioCodec,int height) {
            this.id=id;this.url=url;this.ext=ext;this.videoCodec=videoCodec;this.audioCodec=audioCodec;this.height=height;
        }
    }
    // yt-dlp may supply a fragment-backed format without one standalone URL.
    static final class Score implements Comparable<Score> {
        int videoCount, audioCount, maxHeight;
        boolean hasMedia() { return videoCount>0 || audioCount>0; }
        boolean hasVideoAndAudio() { return videoCount>0 && audioCount>0; }
        @Override public int compareTo(Score other) {
            int heights=Integer.compare(maxHeight,other.maxHeight);
            if (heights!=0) return heights;
            if (hasVideoAndAudio()!=other.hasVideoAndAudio()) return hasVideoAndAudio()?1:-1;
            int videos=Integer.compare(videoCount,other.videoCount);
            return videos!=0 ? videos : Integer.compare(audioCount,other.audioCount);
        }
    }
    static Score score(List<Format> formats) {
        Score score=new Score();
        for (Format format:formats) {
            boolean byId=format.id!=null && format.id.matches("[A-Za-z0-9_.:-]+");
            boolean byUrl=format.url!=null && (format.url.startsWith("https://") || format.url.startsWith("http://"));
            if (!byId && !byUrl) continue;
            String ext=format.ext==null?"":format.ext.toLowerCase(Locale.ROOT);
            if(ext.equals("mhtml") || ext.equals("jpg") || ext.equals("png") || ext.equals("webp")) continue;
            boolean video=present(format.videoCodec) && !"images".equalsIgnoreCase(format.videoCodec);
            boolean audio=present(format.audioCodec);
            if(video) {score.videoCount++;score.maxHeight=Math.max(score.maxHeight,format.height);}
            if(audio) score.audioCount++;
        }
        return score;
    }
    static boolean present(String codec) { return codec!=null && !codec.isEmpty() && !codec.equalsIgnoreCase("none"); }
}
