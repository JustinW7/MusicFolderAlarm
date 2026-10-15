package com.musicfolderalarm.app;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android-owned playback that does not depend on WebView activity. */
public final class PlaybackService extends Service {
    public static final String ACTION_ALARM="com.musicfolderalarm.app.ALARM_PLAY";
    public static final String ACTION_MANUAL="com.musicfolderalarm.app.MANUAL_PLAY";
    public static final String ACTION_TOGGLE="com.musicfolderalarm.app.TOGGLE";
    public static final String ACTION_PAUSE="com.musicfolderalarm.app.PAUSE";
    public static final String ACTION_RESUME="com.musicfolderalarm.app.RESUME";
    public static final String ACTION_NEXT="com.musicfolderalarm.app.NEXT";
    public static final String ACTION_PREVIOUS="com.musicfolderalarm.app.PREVIOUS";
    public static final String ACTION_VOLUME="com.musicfolderalarm.app.VOLUME";
    public static final String ACTION_REPEAT="com.musicfolderalarm.app.REPEAT";
    public static final String ACTION_SEEK="com.musicfolderalarm.app.SEEK";
    public static final String ACTION_UPDATE="com.musicfolderalarm.app.PLAYBACK_UPDATE";
    public static final String ACTION_STOP="com.musicfolderalarm.app.STOP";
    private static final String CHANNEL="musicfolderalarm_media_v9";
    private static final int NOTICE=3401;
    private static volatile PlaybackService active;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService scanner=Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock prepareLock;
    private JSONArray queue=new JSONArray();
    private int index=0,sessionGeneration=0,failedInQueue=0;
    private boolean playing=false,alarmMode=false,repeatOne=false,prepared=false;
    private String alarmName="",alarmId="";
    private long deliveredAt=0;

    @Override public void onCreate(){
        super.onCreate();active=this;
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Music playback",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Music controls in the Android notification drawer");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        session=new MediaSession(this,"MusicFolderAlarmSession");
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){handler.post(()->resume());}
            @Override public void onPause(){handler.post(()->pause());}
            @Override public void onSkipToNext(){handler.post(()->move(1));}
            @Override public void onSkipToPrevious(){handler.post(()->move(-1));}
            @Override public void onSeekTo(long pos){handler.post(()->seekMs(pos));}
            @Override public void onStop(){handler.post(()->stopSelf());}
        });
        session.setActive(true);
        updateSession();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();if(action==null)return START_NOT_STICKY;
        try{
            switch(action){
                case ACTION_ALARM:{
                    JSONObject alarm=new JSONObject(intent.getStringExtra("alarm"));
                    alarmMode=true;alarmName=alarm.optString("name","Scheduled alarm");alarmId=alarm.optString("id","");
                    deliveredAt=intent.getLongExtra("deliveredAt",0);
                    repeatOne=false;failedInQueue=0;
                    int initial=alarm.optInt("volumeLevel",12);
                    int lo=alarm.optBoolean("volumeVariable",true)?Math.max(10,alarm.optInt("volumeMin",10)):1;
                    int hi=alarm.optBoolean("volumeVariable",true)?Math.max(lo,alarm.optInt("volumeMax",16)):16;
                    setDeviceVolume(Math.max(lo,Math.min(hi,initial)));
                    final int generation=++sessionGeneration;
                    // Promote promptly; folder scanning is off the main thread.
                    stopPlayer();queue=new JSONArray();index=0;playing=false;
                    ensureForeground();
                    final String folderId=alarm.optString("folderId","");
                    scanner.execute(()->{
                        JSONArray tracks=TrackLibrary.tracks(getApplicationContext(),folderId);
                        if(tracks==null)tracks=new JSONArray();
                        final JSONArray raw=tracks;
                        handler.post(()->{
                            if(generation!=sessionGeneration)return;
                            queue=alarm.optBoolean("shuffle",true)?smartShuffle(raw,alarmId):raw;
                            failedInQueue=0;index=0;playIndex(0);
                        });
                    });
                    break;
                }
                case ACTION_MANUAL:{
                    alarmMode=false;alarmName="";alarmId="";deliveredAt=0;failedInQueue=0;
                    final int generation=++sessionGeneration;
                    final String queueName=intent.getStringExtra("queueFile");
                    final int wanted=intent.getIntExtra("index",0);
                    if(queueName==null || !queueName.matches("mfa_queue_[A-Za-z0-9_-]+\\.json"))
                        throw new IllegalArgumentException("Invalid playback queue filename");
                    // Foreground promotion happens before potentially expensive disk parsing.
                    stopPlayer();queue=new JSONArray();index=0;ensureForeground();
                    setDeviceVolume(intent.getIntExtra("volumeStep",12));
                    scanner.execute(()->{
                        JSONArray result=null;
                        java.io.File file=new java.io.File(getCacheDir(),queueName);
                        try {
                            result=new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8));
                        }catch(Exception e){Log.e("MusicPlaybackService","Unable to read queue",e);}
                        finally{if(file.exists()&&!file.delete())Log.w("MusicPlaybackService","Old queue not deleted");}
                        final JSONArray loaded=result;
                        handler.post(()->{
                            if(generation!=sessionGeneration)return;
                            if(loaded==null){fail("Cannot load music folder queue");return;}
                            queue=loaded;failedInQueue=0;playIndex(wanted);
                        });
                    });
                    break;
                }
                case ACTION_NEXT:move(1);break;
                case ACTION_PREVIOUS:move(-1);break;
                case ACTION_TOGGLE:if(playing)pause();else resume();break;
                case ACTION_PAUSE:pause();break;
                case ACTION_RESUME:resume();break;
                case ACTION_STOP:stopSelf();break;
                case ACTION_REPEAT:repeatOne=intent.getBooleanExtra("enabled",false);break;
                case ACTION_VOLUME:setDeviceVolume(intent.getIntExtra("step",12));break;
                case ACTION_SEEK:if(prepared&&player!=null&&player.getDuration()>0){int percent=Math.max(0,Math.min(100,intent.getIntExtra("percent",0)));seekMs(player.getDuration()*percent/100L);}break;
            }
        }catch(Exception exception){
            Log.e("MusicPlaybackService","Playback action failed",exception);
            EventLog.add(this,"error","Playback service exception",exception.toString(),alarmId);
            broadcast("error",exception.toString());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Prefer different starting tracks, then cycle all tracks. Persists history per alarm. */
    private JSONArray smartShuffle(JSONArray raw,String id){
        if(raw.length()<2)return raw;
        try{
            android.content.SharedPreferences p=getSharedPreferences("shuffle",MODE_PRIVATE);
            JSONArray used=new JSONArray(p.getString("used_"+id,"[]"));
            HashSet<String> usedIds=new HashSet<>();
            for(int i=0;i<used.length();i++)usedIds.add(used.optString(i));
            String last=p.getString("last_"+id,"");
            ArrayList<JSONObject> all=new ArrayList<>();
            for(int i=0;i<raw.length();i++)all.add(raw.getJSONObject(i));
            ArrayList<JSONObject> candidates=new ArrayList<>();
            for(JSONObject t:all){
                if(!usedIds.contains(t.optString("id"))&&!t.optString("id").equals(last))candidates.add(t);
            }
            if(candidates.isEmpty()){
                used=new JSONArray();
                for(JSONObject t:all)if(!t.optString("id").equals(last))candidates.add(t);
            }
            if(candidates.isEmpty())candidates.addAll(all);
            Collections.shuffle(candidates);
            JSONObject start=candidates.get(0);
            used.put(start.optString("id"));
            p.edit().putString("last_"+id,start.optString("id")).putString("used_"+id,used.toString()).apply();
            all.removeIf(item->item.optString("id").equals(start.optString("id")));
            Collections.shuffle(all);
            JSONArray result=new JSONArray().put(start);
            for(JSONObject item:all)result.put(item);
            return result;
        }catch(Exception e){Log.e("MusicPlaybackService","Shuffle history error",e);return raw;}
    }

    private void setDeviceVolume(int step){
        int level=Math.max(1,Math.min(16,step));
        try{
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int actual=Math.max(1,Math.min(max,Math.round(level*max/16f)));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,actual,0);
        }catch(Exception e){Log.w("MusicPlaybackService","Unable to set device media volume",e);}
    }
    private void requestFocus(){
        if(focus!=null)audio.abandonAudioFocusRequest(focus);
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{
                if(change==AudioManager.AUDIOFOCUS_LOSS)handler.post(this::pause);
            }).build();
        audio.requestAudioFocus(focus);
    }
    private void stopPlayer() {
        MediaPlayer oldPlayer = player;
        player = null;
        playing = false;
        prepared = false;

        if (oldPlayer != null) {
            try {
                oldPlayer.setOnPreparedListener(null);
                oldPlayer.setOnCompletionListener(null);
                oldPlayer.setOnErrorListener(null);
            } catch (Exception e) {
                Log.w("MusicPlaybackService", "Could not detach playback callbacks", e);
            }
            try {
                oldPlayer.release();
            } catch (Exception e) {
                Log.w("MusicPlaybackService", "Player cleanup failed", e);
            }
        }
        if (prepareLock != null && prepareLock.isHeld()) {
            prepareLock.release();
        }
        prepareLock = null;
    }
    private void playIndex(int wanted){
        if(queue.length()==0){fail("No readable audio tracks in selected folder");return;}
        index=Math.floorMod(wanted,queue.length());stopPlayer();
        try{
            JSONObject track=queue.getJSONObject(index);
            String uri=track.optString("file","");
            if(!uri.startsWith("content://"))throw new IllegalArgumentException("Selected track is not a device URI");
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            prepareLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MusicFolderAlarm:AudioPreparation");
            prepareLock.acquire(25000);
            requestFocus();
            player=new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this,Uri.parse(uri));
            player.setOnPreparedListener(mp->{
                if (mp != player) return; // Ignore obsolete async callbacks.
                prepared=true;
                if(prepareLock!=null&&prepareLock.isHeld())prepareLock.release();
                prepareLock=null;
                mp.start();playing=true;failedInQueue=0;
                if(alarmMode){
                    EventLog.add(this,"success",alarmName+": music started",
                        "Actual start "+System.currentTimeMillis()+"; scheduled trigger delivered "+deliveredAt+"; track "+track.optString("name"),alarmId);
                    alarmMode=false; // Keeps playback going as a normal foreground media session.
                }
                updateNotification();broadcast("state","");
            });
            player.setOnCompletionListener(mp->{
                if (mp != player) return;
                if (repeatOne) playIndex(index); else move(1);
            });
            player.setOnErrorListener((mp,what,extra)->{
                if (mp != player) return true;
                skipUnavailable("Audio decode error "+what+"/"+extra);return true;
            });
            player.prepareAsync();
            updateNotification();broadcast("loading","");
        }catch(Exception e){skipUnavailable("Cannot open selected track: "+e.getMessage());}
    }
    private void skipUnavailable(String reason){
        Log.w("MusicPlaybackService",reason);
        if(++failedInQueue>=queue.length()){fail(reason+" (all tracks failed)");return;}
        playIndex(index+1);
    }
    private void fail(String detail){
        EventLog.add(this,"error",alarmName.isEmpty()?"Music unavailable":alarmName+": music unavailable",detail,alarmId);
        broadcast("error",detail);stopSelf();
    }
    private void move(int direction){if(queue.length()>0){failedInQueue=0;playIndex(index+direction);}}
    private void pause(){if(prepared&&player!=null&&playing){player.pause();playing=false;updateNotification();broadcast("state","");}}
    private void resume(){if(prepared&&player!=null){player.start();playing=true;updateNotification();broadcast("state","");}}
    private void seekMs(long pos){if(prepared&&player!=null){player.seekTo((int)Math.min(Integer.MAX_VALUE,pos));broadcast("state","");}}

    private PendingIntent command(String action,int code){
        Intent i=new Intent(this,PlaybackService.class).setAction(action);
        return PendingIntent.getService(this,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private void updateSession(){
        if(session==null)return;
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|
                     PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO;
        PlaybackState playback=new PlaybackState.Builder().setActions(actions)
             .setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,
                 prepared&&player!=null?Math.max(0,player.getCurrentPosition()):0,playing?1f:0f).build();
        session.setPlaybackState(playback);
        String title="Music Folder Alarm";
        try{title=queue.getJSONObject(index).optString("name",title);}catch(Exception ignored){}
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,alarmName.isEmpty()?"Local music":"Alarm · "+alarmName).build());
    }
    private Notification makeNotification(){
        updateSession();
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content=PendingIntent.getActivity(this,900,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title="Music Folder Alarm";
        try{title=queue.getJSONObject(index).optString("name",title);}catch(Exception ignored){}
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(alarmName.isEmpty()?"Music playback":"Alarm · "+alarmName)
            .setContentIntent(content).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"Previous",command(ACTION_PREVIOUS,901)).build())
            .addAction(new Notification.Action.Builder(playing?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing?"Pause":"Play",command(ACTION_TOGGLE,902)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"Next",command(ACTION_NEXT,903)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2))
            .build();
    }
    private void ensureForeground(){startForeground(NOTICE,makeNotification());}
    private void updateNotification(){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,makeNotification());
    }
    private void broadcast(String type,String message){
        try{
            JSONObject obj=new JSONObject();
            // Keep broadcast + SharedPreferences state small. Sending a full queue
            // here would hit the same Binder limit after playback starts.
            obj.put("type",type).put("playing",playing).put("index",index);
            obj.put("queueSize",queue.length());
            JSONObject current=queue.optJSONObject(index);
            if(current!=null)obj.put("track",current);
            obj.put("position",prepared&&player!=null?Math.max(0,player.getCurrentPosition()):0);
            obj.put("duration",prepared&&player!=null?Math.max(0,player.getDuration()):0);
            obj.put("alarmName",alarmName).put("message",message==null?"":message);
            getSharedPreferences("player",MODE_PRIVATE).edit().putString("last",obj.toString()).apply();
            Intent update=new Intent(ACTION_UPDATE).setPackage(getPackageName()).putExtra("payload",obj.toString());
            sendBroadcast(update);
        }catch(Exception e){Log.w("MusicPlaybackService","Could not broadcast player state",e);}
    }
    public static String progress(){
        PlaybackService service=active;
        JSONObject result=new JSONObject();
        if(service!=null&&service.prepared&&service.player!=null)try{
            result.put("position",service.player.getCurrentPosition());result.put("duration",service.player.getDuration());
        }catch(Exception ignored){}
        return result.toString();
    }
    @Override public void onDestroy(){
        active=null;++sessionGeneration;
        stopPlayer();scanner.shutdownNow();
        if(focus!=null)audio.abandonAudioFocusRequest(focus);
        if(session!=null){session.setActive(false);session.release();session=null;}
        broadcast("stopped","");super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
