package com.satlink.geckoviewtest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Local browser session/history/settings; independent of download and media engines. */
final class BrowserWorkspace {
    static final int MAX_TABS = 100, MAX_HISTORY = 1000, MAX_CLOSED = 20;
    static final class Page {
        final String url, title, state;
        final long time;
        Page(String url,String title,String state,long time) {
            this.url=url;this.title=title==null?"":title;
            this.state=state!=null && state.length()<=32768?state:"";this.time=time;
        }
    }
    final List<Page> tabs=new ArrayList<>(), history=new ArrayList<>(), closed=new ArrayList<>();
    int selected;
    String engine="Google";
    boolean restoreTabs=true;

    static boolean restorable(String url) {
        if("about:blank".equals(url)) return true;
        try {URI u=URI.create(url);return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost()!=null && u.getUserInfo()==null;}catch(Exception e){return false;}
    }
    void visit(String url,String title,long time) {
        if(!restorable(url) || "about:blank".equals(url)) return;
        history.removeIf(p->p.url.equals(url));history.add(0,new Page(url,title,"",time));
        while(history.size()>MAX_HISTORY) history.remove(history.size()-1);
    }
    void rememberClosed(String url,String title,String state) {
        if(!restorable(url) || "about:blank".equals(url)) return;
        closed.add(0,new Page(url,title,state,System.currentTimeMillis()));
        while(closed.size()>MAX_CLOSED) closed.remove(closed.size()-1);
    }
    Page reopen() {return closed.isEmpty()?null:closed.remove(0);}
    List<Page> matching(String query) {
        String key=query==null?"":query.toLowerCase(Locale.ROOT).trim();
        List<Page> out=new ArrayList<>();
        for(Page p:history) if(key.isEmpty() || (p.title+" "+p.url).toLowerCase(Locale.ROOT).contains(key)) out.add(p);
        return out;
    }
    String search(String query) {
        String prefix="Bing".equals(engine)?"https://www.bing.com/search?q=":"DuckDuckGo".equals(engine)?"https://duckduckgo.com/?q=":"https://www.google.com/search?q=";
        return prefix+URLEncoder.encode(query,StandardCharsets.UTF_8);
    }
    String encode() {
        StringBuilder b=new StringBuilder("SATLINK_BROWSER_1\n");
        b.append("P\t").append(selected).append('\t').append(restoreTabs).append('\t').append(engine).append('\n');
        append(b,"T",tabs,MAX_TABS);append(b,"C",closed,MAX_CLOSED);append(b,"H",history,MAX_HISTORY);
        return b.toString();
    }
    private static void append(StringBuilder b,String type,List<Page> pages,int max) {
        int count=0;
        for(Page p:pages) {
            if(count>=max)break;
            if(!restorable(p.url)) continue;
            b.append(type).append('\t').append(text(p.url)).append('\t').append(text(p.title)).append('\t').append(text(p.state)).append('\t').append(p.time).append('\n');count++;
        }
    }
    static BrowserWorkspace decode(String raw) {
        BrowserWorkspace w=new BrowserWorkspace();
        if(raw==null || raw.length()>8_000_000 || !raw.startsWith("SATLINK_BROWSER_1\n")) return w;
        for(String line:raw.split("\n")) {
            try {
                String[] f=line.split("\t",-1);
                if(f.length==4 && "P".equals(f[0])) {w.selected=Integer.parseInt(f[1]);w.restoreTabs=Boolean.parseBoolean(f[2]);if("Google".equals(f[3])||"Bing".equals(f[3])||"DuckDuckGo".equals(f[3]))w.engine=f[3];}
                else if(f.length==5) {
                    String url=plain(f[1]);if(!restorable(url))continue;
                    Page p=new Page(url,plain(f[2]),plain(f[3]),Long.parseLong(f[4]));
                    List<Page> list="T".equals(f[0])?w.tabs:"C".equals(f[0])?w.closed:"H".equals(f[0])?w.history:null;
                    int max="T".equals(f[0])?MAX_TABS:"C".equals(f[0])?MAX_CLOSED:MAX_HISTORY;
                    if(list!=null && list.size()<max)list.add(p);
                }
            }catch(Exception ignored){}
        }
        w.selected=Math.max(0,Math.min(w.selected,Math.max(0,w.tabs.size()-1)));return w;
    }
    private static String text(String s) {return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static String plain(String s) {return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
}
