package com.musicfolderalarm.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Durable delivery and audio-start diagnostics; survives process death. */
public final class EventLog {
    private static final String PREF="alarm_events";
    private EventLog() {}
    public static synchronized void add(Context context,String type,String title,String detail,String alarmId){
        try {
            SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            JSONArray prior=new JSONArray(prefs.getString("items","[]"));
            JSONArray recent=new JSONArray();
            JSONObject entry=new JSONObject();
            long when=System.currentTimeMillis();
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            entry.put("id",when+"-"+System.nanoTime());
            entry.put("at",fmt.format(new Date(when)));
            entry.put("type",type).put("title",title).put("detail",detail).put("alarmId",alarmId==null?"":alarmId);
            recent.put(entry);
            for(int i=0;i<prior.length()&&i<49;i++)recent.put(prior.getJSONObject(i));
            prefs.edit().putString("items",recent.toString()).commit();
            Log.i("MusicFolderAlarm",type+": "+title+" - "+detail);
        }catch(Exception exception){Log.w("MusicFolderAlarm","Could not write event log",exception);}
    }
    public static String items(Context context){return context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("items","[]");}
}
