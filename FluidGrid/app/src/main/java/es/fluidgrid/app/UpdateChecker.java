package es.fluidgrid.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Only checks GitHub Releases; never silently installs anything or requests account keys. */
public final class UpdateChecker {
    private static final String API="https://api.github.com/repos/lazgom-hash/miBot-BTCEUR-Android/releases?per_page=30";
    private static final String TRUSTED="https://github.com/lazgom-hash/miBot-BTCEUR-Android/releases/download/";
    public interface Callback { void onResult(Result result); }
    public static final class Result {
        public final String version,downloadUrl,message;
        public final boolean newer;
        Result(String version,String downloadUrl,String message,boolean newer){
            this.version=version;this.downloadUrl=downloadUrl;this.message=message;this.newer=newer;
        }
    }
    private UpdateChecker(){}
    private static int compare(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            int u=0,v=0;
            try{u=i<x.length?Integer.parseInt(x[i].replaceAll("[^0-9].*","")):0;}catch(Exception ignored){}
            try{v=i<y.length?Integer.parseInt(y[i].replaceAll("[^0-9].*","")):0;}catch(Exception ignored){}
            if(u!=v)return Integer.compare(u,v);
        }
        return 0;
    }
    public static void check(Context context,Callback callback){
        String current="0.0.0";
        try{current=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}
        final String installed=current;
        new Thread(()->{
            Result response;
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(API).openConnection();
                c.setConnectTimeout(10000);c.setReadTimeout(12000);
                c.setRequestProperty("Accept","application/vnd.github+json");
                c.setRequestProperty("User-Agent","FluidGrid-Android-updater/1.7");
                if(c.getResponseCode()!=200)throw new IllegalStateException("GitHub HTTP "+c.getResponseCode());
                StringBuilder buffer=new StringBuilder();
                try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                    String line;while((line=r.readLine())!=null)buffer.append(line);
                }
                JSONArray releases=new JSONArray(buffer.toString());
                String best=installed,bestUrl="";
                for(int i=0;i<releases.length();i++){
                    JSONObject rel=releases.optJSONObject(i);if(rel==null||rel.optBoolean("draft"))continue;
                    String tag=rel.optString("tag_name","");if(!tag.startsWith("fluidgrid-v"))continue;
                    String version=tag.substring("fluidgrid-v".length());
                    if(compare(version,best)<=0)continue;
                    JSONArray assets=rel.optJSONArray("assets");if(assets==null)continue;
                    for(int j=0;j<assets.length();j++){
                        JSONObject asset=assets.optJSONObject(j);if(asset==null)continue;
                        String url=asset.optString("browser_download_url","");
                        if(url.startsWith(TRUSTED)&&url.endsWith(".apk")){
                            best=version;bestUrl=url;break;
                        }
                    }
                }
                response=bestUrl.isEmpty()?
                    new Result(installed,"","No hay una versión posterior publicada en GitHub.",false):
                    new Result(best,bestUrl,"Actualización disponible: "+best+". Descarga y confirma su instalación en Android.",true);
            }catch(Exception e){
                response=new Result(installed,"","No se pudo consultar GitHub: "+e.getMessage(),false);
            }finally{if(c!=null)c.disconnect();}
            Result result=response;
            new Handler(Looper.getMainLooper()).post(()->callback.onResult(result));
        },"FluidGrid-update-check").start();
    }
}