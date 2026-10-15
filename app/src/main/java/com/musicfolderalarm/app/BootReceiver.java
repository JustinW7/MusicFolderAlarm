package com.musicfolderalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        final PendingResult pending=goAsync();
        new Thread(()->{
            try{
                EventLog.add(context,"warning","Alarm schedule restored","System event: "+intent.getAction(),"");
                AlarmScheduler.restore(context.getApplicationContext());
            }finally{pending.finish();}
        },"RestoreAlarmClock").start();
    }
}
