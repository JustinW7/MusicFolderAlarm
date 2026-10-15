package com.musicfolderalarm.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class FolderScanner {
    private FolderScanner(){}
    public static JSONObject scan(Context context, Uri tree) {
        JSONObject result=new JSONObject();JSONArray tracks=new JSONArray();
        try {
            String folderId="tree:"+tree;
            String parentDoc=DocumentsContract.getTreeDocumentId(tree);
            String title=parentDoc.contains(":")?parentDoc.substring(parentDoc.lastIndexOf(':')+1):"Music folder";
            if(title.isEmpty())title="Music folder";
            result.put("id",folderId).put("name",title).put("tracks",tracks);
            enumerate(context,tree,parentDoc,folderId,title,tracks,0);
            result.put("count",tracks.length());
        }catch(Exception e){Log.e("MusicFolderScanner","Scan error",e);}
        return result;
    }
    private static void enumerate(Context c,Uri tree,String parent,String folderId,String folderName,JSONArray out,int depth){
        if(depth>64)return;
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
        try(Cursor rows=c.getContentResolver().query(children,projection,null,null,null)){
            if(rows==null)return;
            while(rows.moveToNext()){
                String id=rows.getString(0), name=rows.getString(1),mime=rows.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
                    enumerate(c,tree,id,folderId,folderName,out,depth+1);continue;
                }
                String lower=(name==null?"":name).toLowerCase(Locale.ROOT);
                if(!(mime!=null&&mime.startsWith("audio/")||lower.matches(".*\\.(mp3|m4a|wav|ogg|flac|aac|opus|webm|mp4)$")))continue;
                Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                String clean=name.replaceFirst("\\.[^.]+$","").replace('_',' ');
                JSONObject track=new JSONObject();
                track.put("id","doc:"+document).put("name",clean).put("artist","Local audio")
                     .put("file",document.toString()).put("folderId",folderId).put("folderName",folderName)
                     .put("duration",0);
                out.put(track);
            }
        }catch(Exception e){Log.w("MusicFolderScanner","Cannot open folder level",e);}
    }
    public static JSONArray tracks(Context c,String folderId){
        if(folderId==null||!folderId.startsWith("tree:"))return new JSONArray();
        return scan(c,Uri.parse(folderId.substring(5))).optJSONArray("tracks");
    }
}
