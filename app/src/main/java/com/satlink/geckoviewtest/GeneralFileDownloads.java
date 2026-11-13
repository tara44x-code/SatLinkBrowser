package com.satlink.geckoviewtest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Uses Android's file download manager independently of the video/audio service. */
final class GeneralFileDownloads {
    static final int STORAGE_PERMISSION = 4093;
    private final Activity activity;
    private Pending pendingPermission;
    private String showingUrl;

    GeneralFileDownloads(Activity activity) {this.activity=activity;}

    boolean navigation(String url, String referer, String userAgent, String cookies) {
        if (url!=null && url.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            offerMagnet(url);return true;
        }
        if (!GeneralFilePolicy.directFile(url)) return false;
        offer(url,"","",0L,referer,userAgent,cookies);
        return true;
    }

    void offer(String url, String disposition, String suppliedMime, long size,
               String referer, String userAgent, String cookies) {
        if (activity.isFinishing() || activity.isDestroyed() || !GeneralFilePolicy.http(url)) return;
        if (url.equals(showingUrl)) return;
        String name=GeneralFilePolicy.fileName(url,disposition,suppliedMime);
        String mime=GeneralFilePolicy.mime(name,suppliedMime);
        Pending pending=new Pending(url,name,mime,referer,userAgent,
                GeneralFilePolicy.cookieHeader(cookies,url,System.currentTimeMillis()/1000L));
        String sizeText=size>0?size(size):"يظهر الحجم أثناء التنزيل";
        showingUrl=url;
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("تنزيل ملف")
                .setMessage("الاسم: "+name+"\nالنوع: "+mime+"\nالحجم: "+sizeText+"\nالحفظ: Downloads/SatLink")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("تنزيل",(d,which)->enqueue(pending))
                .create();
        dialog.setOnDismissListener(d->showingUrl=null);
        dialog.show();
    }

    void permissionResult(int requestCode, int[] grantResults) {
        if(requestCode!=STORAGE_PERMISSION) return;
        Pending pending=pendingPermission;pendingPermission=null;
        if(pending==null) return;
        if(grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) enqueue(pending);
        else toast("يلزم السماح بحفظ الملفات لبدء التنزيل");
    }

    private void enqueue(Pending pending) {
        if (Build.VERSION.SDK_INT<=Build.VERSION_CODES.P
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            pendingPermission=pending;
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_PERMISSION);
            return;
        }
        try {
            DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if(manager==null) throw new IllegalStateException("مدير التنزيل غير متاح");
            String name=uniqueName(pending.name,manager);
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(pending.url));
            request.setTitle(name);
            request.setDescription("SatLink Browser — تنزيل ملف");
            request.setMimeType(pending.mime);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"SatLink/"+name);
            if(!pending.userAgent.isEmpty()) request.addRequestHeader("User-Agent",pending.userAgent);
            if(GeneralFilePolicy.http(pending.referer)) request.addRequestHeader("Referer",pending.referer);
            if(!pending.cookies.isEmpty()) request.addRequestHeader("Cookie",pending.cookies);
            manager.enqueue(request);
            toast("بدأ تنزيل "+name+" — تابع التقدم من إشعار التنزيل");
        } catch(Exception error) {toast("تعذر بدء تنزيل الملف: "+error.getMessage());}
    }

    private String uniqueName(String wanted, DownloadManager manager) {
        String safe=GeneralFilePolicy.safeName(wanted);
        int dot=safe.lastIndexOf('.');
        String stem=dot>0?safe.substring(0,dot):safe;
        String suffix=dot>0?safe.substring(dot):"";
        for(int index=0;index<10000;index++) {
            String name=index==0?safe:stem+" ("+index+")"+suffix;
            if(!exists(name,manager)) return name;
        }
        return stem+" ("+System.currentTimeMillis()+")"+suffix;
    }

    private boolean exists(String name, DownloadManager manager) {
        // Include queued and running files, not only already completed files.
        try(Cursor cursor=manager.query(new DownloadManager.Query())) {
            if(cursor!=null) {
                int title=cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE);
                while(cursor.moveToNext()) if(name.equals(cursor.getString(title))) return true;
            }
        } catch(Exception ignored) { }
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
            try(Cursor cursor=activity.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},MediaStore.Downloads.DISPLAY_NAME+"=? AND "+MediaStore.Downloads.RELATIVE_PATH+"=?",
                    new String[]{name,Environment.DIRECTORY_DOWNLOADS+"/SatLink/"},null)) {
                return cursor!=null && cursor.moveToFirst();
            } catch(Exception ignored) {return false;}
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"SatLink/"+name).exists();
    }

    private void offerMagnet(String url) {
        if(activity.isFinishing() || activity.isDestroyed() || url.equals(showingUrl)) return;
        showingUrl=url;
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("رابط تورنت")
                .setMessage("ملفات .torrent تُنزّل كملفات. رابط magnet يحتاج تطبيق تورنت لتحميل محتوياته.")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("فتح بتطبيق تورنت",(d,which)->{
                    try {activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
                    catch(Exception error) {copyLink(url);}
                }).create();
        dialog.setOnDismissListener(d->showingUrl=null);dialog.show();
    }

    /** Without a torrent app the magnet link is still usable: keep it on the clipboard. */
    private void copyLink(String url) {
        try {
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("magnet",url));
            toast("لا يوجد تطبيق تورنت مثبت. تم نسخ رابط magnet؛ ثبّت تطبيق تورنت ثم الصقه فيه");
        } catch(Exception error) {toast("لا يوجد تطبيق تورنت مناسب مثبت على الجهاز");}
    }

    private void toast(String text) {Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private static String size(long bytes) {
        if(bytes<1024) return bytes+" B";
        double value=bytes;String unit="B";
        for(String next:new String[]{"KB","MB","GB","TB"}) {value/=1024d;unit=next;if(value<1024) break;}
        return String.format(Locale.US,"%.1f %s",value,unit);
    }

    private static final class Pending {
        final String url,name,mime,referer,userAgent,cookies;
        Pending(String url,String name,String mime,String referer,String userAgent,String cookies) {
            this.url=url;this.name=name;this.mime=mime;this.referer=referer==null?"":referer;
            this.userAgent=userAgent==null?"":userAgent;this.cookies=cookies;
        }
    }
}
