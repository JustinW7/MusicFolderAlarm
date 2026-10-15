package com.musicfolderalarm.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;

/** Schedules durable, system-owned AlarmClock alarms (never WebView timers). */
public final class AlarmScheduler {
    private static final String PREFS="native_alarms";
    private static final String TAG="MusicAlarmScheduler";
    private AlarmScheduler(){}
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static String alarmId(JSONObject alarm){return alarm.optString("id","");}
    private static int requestId(String id){return id.hashCode()&0x7fffffff;}
    private static PendingIntent fireIntent(Context c,String id,int flags){
        Intent i=new Intent(c,AlarmReceiver.class).setAction("com.musicfolderalarm.app.FIRE");
        i.setData(Uri.parse("musicfolderalarm://alarm/"+Uri.encode(id)));
        i.putExtra("alarmId",id);
        return PendingIntent.getBroadcast(c,requestId(id),i,flags|PendingIntent.FLAG_IMMUTABLE);
    }
    private static String scheduleFingerprint(JSONObject root){
        StringBuilder sb=new StringBuilder().append(root.optBoolean("alarmsMasterEnabled",true));
        JSONArray alarms=root.optJSONArray("alarms");
        if(alarms!=null)for(int i=0;i<alarms.length();i++){
            JSONObject alarm=alarms.optJSONObject(i);if(alarm==null)continue;
            sb.append('|').append(alarm.optString("id")).append('/').append(alarm.optBoolean("enabled",true))
              .append('/').append(alarm.optInt("hour",-1)).append(':').append(alarm.optInt("minute",-1))
              .append('/').append(alarm.optBoolean("once",false)).append('/').append(alarm.optJSONArray("days"));
        }
        return sb.toString();
    }
    public static synchronized void sync(Context c,String json){
        try{update(c,json,false);}catch(Exception e){Log.e(TAG,"Cannot sync alarm schedules",e);}
    }
    public static synchronized void restore(Context c){
        try{update(c,prefs(c).getString("state","{}"),true);}
        catch(Exception e){Log.e(TAG,"Cannot restore alarm schedules",e);}
    }
    private static void update(Context c,String json,boolean force)throws Exception{
        SharedPreferences p=prefs(c);
        String previous=p.getString("state","{}");
        JSONObject root=new JSONObject(json);
        String fingerprint=scheduleFingerprint(root);
        // Changes to playback volume, history, or folders do not cancel and recreate alarms.
        if(!force&&fingerprint.equals(p.getString("fingerprint",""))){
            p.edit().putString("state",json).commit();return;
        }
        if(!p.edit().putString("state",json).commit())throw new IllegalStateException("Could not save alarm state");
        JSONArray old=new JSONObject(previous).optJSONArray("alarms");
        if(old!=null)for(int i=0;i<old.length();i++){
            JSONObject alarm=old.optJSONObject(i);
            if(alarm!=null&&!alarmId(alarm).isEmpty())cancel(c,alarmId(alarm));
        }
        // Setting this before new scheduling would mask failed installs, so do it last.
        if(root.optBoolean("alarmsMasterEnabled",true)){
            JSONArray alarms=root.optJSONArray("alarms");
            if(alarms!=null)for(int i=0;i<alarms.length();i++){
                JSONObject alarm=alarms.optJSONObject(i);if(alarm!=null)scheduleOne(c,alarm);
            }
        }
        p.edit().putString("fingerprint",fingerprint).commit();
    }
    public static void cancel(Context c,String id){
        AlarmManager alarmManager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending=fireIntent(c,id,PendingIntent.FLAG_NO_CREATE);
        if(pending!=null){alarmManager.cancel(pending);pending.cancel();}
    }
    public static synchronized void scheduleById(Context c,String id){
        try{
            JSONObject state=new JSONObject(prefs(c).getString("state","{}"));
            if(!state.optBoolean("alarmsMasterEnabled",true))return;
            JSONArray alarms=state.optJSONArray("alarms");if(alarms==null)return;
            for(int i=0;i<alarms.length();i++){
                JSONObject alarm=alarms.optJSONObject(i);
                if(alarm!=null&&id.equals(alarmId(alarm))){scheduleOne(c,alarm);return;}
            }
        }catch(Exception e){Log.e(TAG,"Cannot reschedule fired alarm",e);}
    }
    public static void markFired(Context c,JSONObject alarm){
        if(alarm.optBoolean("once",false))prefs(c).edit().putString("once_fired_"+alarmId(alarm),alarm.toString()).commit();
    }
    public static JSONObject find(Context c,String id){
        try{
            JSONArray items=new JSONObject(prefs(c).getString("state","{}")).optJSONArray("alarms");
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject alarm=items.optJSONObject(i);
                if(alarm!=null&&id.equals(alarmId(alarm)))return alarm;
            }
        }catch(Exception e){Log.e(TAG,"Could not find alarm",e);}
        return null;
    }
    private static void scheduleOne(Context c,JSONObject alarm){
        if(!alarm.optBoolean("enabled",true))return;
        String id=alarmId(alarm);if(id.isEmpty())return;
        if(alarm.optBoolean("once",false)&&alarm.toString().equals(prefs(c).getString("once_fired_"+id,"")))return;
        int hour=alarm.optInt("hour",-1),minute=alarm.optInt("minute",-1);
        if(hour<0||hour>23||minute<0||minute>59)return;
        Calendar now=Calendar.getInstance();
        long when=-1;
        JSONArray days=alarm.optJSONArray("days");
        for(int offset=0;offset<=7;offset++){
            Calendar day=(Calendar)now.clone();day.add(Calendar.DAY_OF_YEAR,offset);
            day.set(Calendar.HOUR_OF_DAY,hour);day.set(Calendar.MINUTE,minute);
            day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);
            if(day.getTimeInMillis()<=System.currentTimeMillis()+1000)continue;
            boolean matched=alarm.optBoolean("once",false);
            if(!matched&&days!=null)for(int i=0;i<days.length();i++)
                if(days.optInt(i,-1)==day.get(Calendar.DAY_OF_WEEK)-1)matched=true;
            if(matched){when=day.getTimeInMillis();break;}
        }
        if(when<0)return;
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(Build.VERSION.SDK_INT>=31&&!manager.canScheduleExactAlarms()){
            EventLog.add(c,"error","Exact-alarm access required","Enable Alarms & reminders in Android settings.",id);
            return;
        }
        Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent show=PendingIntent.getActivity(c,requestId(id),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent fire=fireIntent(c,id,PendingIntent.FLAG_UPDATE_CURRENT);
        try{
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when,show),fire);
            Log.i(TAG,"Alarm installed: "+id+" at "+when);
        }catch(SecurityException e){
            Log.e(TAG,"Exact-alarm permission missing",e);
            EventLog.add(c,"error","Alarm could not be scheduled",e.toString(),id);
        }
    }
}
