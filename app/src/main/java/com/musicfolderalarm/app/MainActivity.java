package com.musicfolderalarm.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int PICK_TREE=460;
    private static final String TAG="MusicFolderAlarm";
    private WebView webView;
    private String folderIntent="player";
    private boolean receiverRegistered=false;
    private final BroadcastReceiver updates=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            String payload=intent.getStringExtra("payload");
            if(payload!=null)js("window.musicAlarmNativePlayback("+payload+")");
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(16,20,38));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(25,29,50));
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},310);
        webView=new WebView(this);setContentView(webView);
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(true);s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                return !"file".equals(request.getUrl().getScheme());
            }
            @Override public void onPageFinished(WebView view,String url){restoreFolders();restorePlayback();}
        });
        webView.addJavascriptInterface(new Bridge(),"AndroidAlarm");
        webView.loadUrl("file:///android_asset/index.html");
    }
    @Override protected void onStart(){
        super.onStart();
        IntentFilter filter=new IntentFilter(PlaybackService.ACTION_UPDATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates,filter);
        receiverRegistered=true;
    }
    @Override protected void onStop(){
        if(receiverRegistered){unregisterReceiver(updates);receiverRegistered=false;}
        super.onStop();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);restorePlayback();}
    @Override protected void onResume(){super.onResume();
        if(webView!=null){restorePlayback();js("window.musicAlarmNativeEvents("+EventLog.items(this)+")");}
    }
    private void js(String javascript){runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript(javascript,null);});}
    private void restoreFolders(){
        new Thread(()->{
            try{
                String saved=getPreferences(MODE_PRIVATE).getString("folder_uris","[]");
                JSONArray uris=new JSONArray(saved);
                for(int i=0;i<uris.length();i++){
                    Uri uri=Uri.parse(uris.optString(i));
                    JSONObject folder=TrackLibrary.get(this,"tree:"+uri,false);
                    if(folder.optJSONArray("tracks")!=null)sendFolderPages(folder,"restore");
                }
            }catch(Exception e){Log.w(TAG,"Cannot restore some folders",e);}

        },"RestoreMusicFolders").start();
    }
    /** Send small pages to the WebView to avoid a multi-megabyte JS evaluation. */
    private void sendFolderPages(JSONObject folder,String intent){
        JSONArray all=folder.optJSONArray("tracks");
        if(all==null)return;
        JSONObject meta=new JSONObject();
        try {
            meta.put("id",folder.optString("id"));
            meta.put("name",folder.optString("name"));
            meta.put("count",all.length());
        }catch(Exception e){Log.w(TAG,"Invalid music folder metadata",e);return;}
        final int pageSize=200;
        for(int start=0;start<all.length() || start==0;start+=pageSize){
            JSONArray chunk=new JSONArray();
            for(int i=start;i<Math.min(all.length(),start+pageSize);i++)chunk.put(all.optJSONObject(i));
            boolean last=start+pageSize>=all.length();
            js("window.musicAlarmNativeFolderPage("+meta+","+chunk+","+last+","+JSONObject.quote(intent)+")");
        }
    }
    private void restorePlayback(){
        String saved=getSharedPreferences("player",MODE_PRIVATE).getString("last","");
        if(!saved.isEmpty())js("window.musicAlarmNativePlayback("+saved+")");
    }
    private void persistFolder(String uri){
        try{
            JSONArray old=new JSONArray(getPreferences(MODE_PRIVATE).getString("folder_uris","[]"));
            for(int i=0;i<old.length();i++)if(uri.equals(old.optString(i)))return;
            old.put(uri);getPreferences(MODE_PRIVATE).edit().putString("folder_uris",old.toString()).apply();
        }catch(Exception e){Log.e(TAG,"Cannot persist folder",e);}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=PICK_TREE||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri tree=data.getData();
        try{
            int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(tree,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);
            persistFolder(tree.toString());
            String intent=folderIntent;
            new Thread(()->{
                JSONObject folder=TrackLibrary.get(this,"tree:"+tree,true);
                if(folder.optJSONArray("tracks")==null){
                    EventLog.add(this,"error","Folder scan failed",tree.toString(),"");
                    return;
                }
                sendFolderPages(folder,intent);
            },"PickMusicFolder").start();
        }catch(Exception e){Toast.makeText(this,"Could not access folder: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void startAction(String action){
        startService(new Intent(this,PlaybackService.class).setAction(action));
    }
    private final class Bridge {
        @JavascriptInterface public void syncAlarms(String data){AlarmScheduler.sync(getApplicationContext(),data);}
        @JavascriptInterface public void pickFolder(String intent){
            folderIntent=intent;
            runOnUiThread(()->{
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(pick,PICK_TREE);
            });
        }
        @JavascriptInterface public void startPlayback(String tracks,int index,int volumeStep){
            // A track list may exceed Android Binder's transaction limit. Write it to
            // private cache storage and send only the short filename over IPC.
            java.io.File queueFile=null;
            try{
                queueFile=java.io.File.createTempFile("mfa_queue_",".json",getCacheDir());
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(queueFile)){
                    out.write(tracks.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                Intent play=new Intent(MainActivity.this,PlaybackService.class)
                    .setAction(PlaybackService.ACTION_MANUAL)
                    .putExtra("queueFile",queueFile.getName())
                    .putExtra("index",index)
                    .putExtra("volumeStep",volumeStep);
                startForegroundService(play);
            }catch(Exception e){
                if(queueFile!=null)queueFile.delete();
                Log.e(TAG,"Cannot start playback",e);
                EventLog.add(MainActivity.this,"error","Playback request failed",e.toString(),"");
                js("window.musicAlarmNativePlayback({\"type\":\"error\",\"message\":"+
                    JSONObject.quote("Playback request failed: "+e.getMessage())+"})");
            }
        }
        @JavascriptInterface public void pause(){startAction(PlaybackService.ACTION_PAUSE);}
        @JavascriptInterface public void resume(){startAction(PlaybackService.ACTION_RESUME);}
        @JavascriptInterface public void next(){startAction(PlaybackService.ACTION_NEXT);}
        @JavascriptInterface public void previous(){startAction(PlaybackService.ACTION_PREVIOUS);}
        @JavascriptInterface public void seek(int percent){startService(new Intent(MainActivity.this,PlaybackService.class).setAction(PlaybackService.ACTION_SEEK).putExtra("percent",percent));}
        @JavascriptInterface public void getProgress(){js("window.musicAlarmNativeProgress("+PlaybackService.progress()+")");}
        @JavascriptInterface public void setVolumeStep(int step){
            int safe=Math.max(1,Math.min(16,step));
            android.media.AudioManager manager=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);
            try {
                int max=manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,Math.max(1,Math.round(max*safe/16f)),0);
            }catch(Exception e){Log.w(TAG,"Could not adjust media volume",e);}
        }
        @JavascriptInterface public void setRepeatOne(boolean repeat){
            startService(new Intent(MainActivity.this,PlaybackService.class).setAction(PlaybackService.ACTION_REPEAT).putExtra("enabled",repeat));
        }
        @JavascriptInterface public void getEvents(){
            js("window.musicAlarmNativeEvents("+EventLog.items(MainActivity.this)+")");
        }
        @JavascriptInterface public void openAppPermissions(){
            runOnUiThread(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+getPackageName()))));
        }
        @JavascriptInterface public void checkExactAlarmAccess(){
            AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
            boolean allowed=Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms();
            js("window.musicAlarmNativePermission("+allowed+")");
        }
        @JavascriptInterface public void openAlarmSettings(){
            if(Build.VERSION.SDK_INT<31)return;
            runOnUiThread(()->{
                try {startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                catch(Exception ignored){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
            });
        }
    }
    @Override public void onBackPressed(){
        if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();
    }
}
