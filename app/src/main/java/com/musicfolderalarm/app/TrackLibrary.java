package com.musicfolderalarm.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Private, disk-backed scan index for large music folders. Never passed through Intents. */
public final class TrackLibrary {
    private TrackLibrary() {}
    private static final String TAG="MusicTrackLibrary";
    private static File indexFile(Context context, String id) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 0xff));
        File dir=new File(context.getFilesDir(),"track_indexes");
        if(!dir.exists() && !dir.mkdirs() && !dir.isDirectory())throw new java.io.IOException("Cannot create track index directory");
        return new File(dir,hex.toString()+".json");
    }
    public static JSONObject get(Context context,String folderId,boolean refresh) {
        if(folderId==null || !folderId.startsWith("tree:"))return new JSONObject();
        try {
            File target=indexFile(context,folderId);
            if(!refresh && target.isFile()){
                try {
                    JSONObject cached=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
                    if(folderId.equals(cached.optString("id")) && cached.optJSONArray("tracks")!=null)return cached;
                }catch(Exception e){Log.w(TAG,"Index invalid; rescanning folder",e);}
            }
            JSONObject fresh=FolderScanner.scan(context,Uri.parse(folderId.substring(5)));
            if(!folderId.equals(fresh.optString("id")))return fresh;
            File temp=File.createTempFile("mfa_index_",".tmp",target.getParentFile());
            try {
                Files.write(temp.toPath(),fresh.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }catch(java.nio.file.AtomicMoveNotSupportedException e){
                    Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                }
            }finally{if(temp.exists()&&!temp.delete())Log.w(TAG,"Stale temporary index file");}
            Log.i(TAG,"Indexed "+fresh.optInt("count")+" tracks from folder");
            return fresh;
        }catch(Exception e){Log.e(TAG,"Could not load music library",e);return new JSONObject();}
    }
    public static JSONArray tracks(Context context,String id){
        JSONArray result=get(context,id,false).optJSONArray("tracks");
        return result==null?new JSONArray():result;
    }
}
