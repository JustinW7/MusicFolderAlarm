package com.musicfolderalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

/** Registered in manifest: works when app activity and WebView are closed. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        String id=intent.getStringExtra("alarmId");if(id==null)return;
        JSONObject alarm=AlarmScheduler.find(context,id);
        if(alarm==null||!alarm.optBoolean("enabled",true))return;
        try{
            JSONObject state=new JSONObject(AlarmScheduler.prefs(context).getString("state","{}"));
            if(!state.optBoolean("alarmsMasterEnabled",true))return;
            long deliveredAt=System.currentTimeMillis();
            EventLog.add(context,"warning",alarm.optString("name","Alarm")+": delivered",
                "AlarmManager dispatched broadcast at "+deliveredAt+" ms epoch. Awaiting audio playback.",id);
            // Install next recurrence even if music playback fails.
            AlarmScheduler.markFired(context,alarm);
            AlarmScheduler.scheduleById(context,id);
            Intent start=new Intent(context,PlaybackService.class).setAction(PlaybackService.ACTION_ALARM)
                    .putExtra("alarm",alarm.toString()).putExtra("deliveredAt",deliveredAt);
            context.startForegroundService(start);
        }catch(Exception e){
            Log.e("MusicAlarmReceiver","Could not start alarm",e);
            EventLog.add(context,"error","Alarm receiver failed",e.toString(),id);
        }
    }
}
