package es.fluidgrid.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Visible foreground monitoring. Android can still stop dataSync services; never promises 24/7 uptime. */
public final class MarketService extends Service {
    public static final String START="es.fluidgrid.START", PAUSE="es.fluidgrid.PAUSE", ADD="es.fluidgrid.ADD", GAP="es.fluidgrid.GAP";
    private static final String CHANNEL="fluidgrid_monitor", ALERTS="fluidgrid_alerts";
    private final ScheduledExecutorService serial=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pulse;
    private GridEngine engine;
    private String lastAlertKey="";

    @Override public void onCreate(){super.onCreate();engine=new GridEngine(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Simulación FluidGrid",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERTS,"Oportunidades de liquidez",NotificationManager.IMPORTANCE_DEFAULT));
    }
    private Notification ongoing(String detail){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FluidGrid · Simulación BTC").setContentText(detail)
            .setContentIntent(pi).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?START:intent.getAction();if(action==null)action=START;
        if(PAUSE.equals(action)){
            if(pulse!=null)pulse.cancel(false);
            serial.execute(()->{engine.pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});return START_NOT_STICKY;
        }
        if(ADD.equals(action)){
            final double value=intent.getDoubleExtra("amount",0);
            serial.execute(()->{try{engine.addCapital(value);}catch(Exception ex){notifyUser("Aportación rechazada",ex.getMessage());}finally{if(pulse==null)stopSelf();}});
            return START_STICKY;
        }
        if(GAP.equals(action)){
            serial.execute(()->{engine.acknowledgeGap();});return START_STICKY;
        }
        engine.start();
        Notification n=ongoing("Consultando velas cerradas de OKX");
        if(Build.VERSION.SDK_INT>=29)startForeground(11,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(11,n);
        if(pulse==null||pulse.isCancelled())pulse=serial.scheduleWithFixedDelay(this::safelyPoll,0,60,TimeUnit.SECONDS);
        return START_STICKY;
    }
    private void safelyPoll(){
        try{engine.tick();JSONObject x=engine.snapshot();String status=x.optString("status","Activo");
            getSystemService(NotificationManager.class).notify(11,ongoing(status));
            JSONObject advice=x.optJSONObject("advice");
            if(advice!=null&&"Considerar aportación".equals(advice.optString("level"))){
                String key=""+x.optLong("lastMinute")/3_600_000+":"+Math.round(advice.optDouble("suggested")/10);
                if(!key.equals(lastAlertKey)){
                    lastAlertKey=key;
                    notifyUser("FluidGrid · posible aportación",String.format(java.util.Locale.US,"Reserva insuficiente: valorar %.0f USDC virtuales. Abrir para consultar motivos.",advice.optDouble("suggested")));
                }
            }
        }catch(Exception e){getSystemService(NotificationManager.class).notify(11,ongoing("Sin datos fiables · "+e.getMessage()));}
    }
    private void notifyUser(String title,String message){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,ALERTS).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message).setAutoCancel(true).setContentIntent(pi).build();
        getSystemService(NotificationManager.class).notify(12,n);
    }
    @Override public void onDestroy(){if(pulse!=null)pulse.cancel(true);serial.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}