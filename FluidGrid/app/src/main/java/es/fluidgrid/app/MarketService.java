package es.fluidgrid.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
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
import org.json.JSONArray;
import java.text.NumberFormat;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Visible foreground monitoring. Android can still stop dataSync services; never promises 24/7 uptime. */
public final class MarketService extends Service {
    public static final String START="es.fluidgrid.START", PAUSE="es.fluidgrid.PAUSE", ADD="es.fluidgrid.ADD", GAP="es.fluidgrid.GAP";
    private static final String CHANNEL="fluidgrid_monitor", ALERTS="fluidgrid_alerts";
    private static final String BUY_CHANNEL="fluidgrid_buy_v1", SELL_CHANNEL="fluidgrid_sell_v1";
    private final ScheduledExecutorService serial=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pulse;
    private GridEngine engine;
    private String lastAlertKey="";

    @Override public void onCreate(){super.onCreate();engine=new GridEngine(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Simulación FluidGrid",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERTS,"Oportunidades de liquidez",NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel buys=new NotificationChannel(BUY_CHANNEL,"Compras simuladas",NotificationManager.IMPORTANCE_HIGH);
        buys.setDescription("Avisos de compras BTC al tocar zonas verdes de FluidTrades");buys.enableVibration(true);
        nm.createNotificationChannel(buys);
        NotificationChannel sells=new NotificationChannel(SELL_CHANNEL,"Ventas simuladas",NotificationManager.IMPORTANCE_HIGH);
        sells.setDescription("Avisos de ventas BTC rentables al tocar zonas rojas de FluidTrades");sells.enableVibration(true);
        nm.createNotificationChannel(sells);
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
                    notifyUser("FluidGrid · posible aportación",String.format(Locale.US,"Reserva insuficiente: valorar %.0f USDC virtuales. Abrir para consultar motivos.",advice.optDouble("suggested")));
                }
            }
        }catch(Exception e){getSystemService(NotificationManager.class).notify(11,ongoing("Sin datos fiables · "+e.getMessage()));}
        finally { notifyTrades(); }
    }
    private String decimal(double value,int decimals){
        NumberFormat n=NumberFormat.getNumberInstance(new Locale("es","ES"));
        n.setMinimumFractionDigits(decimals);n.setMaximumFractionDigits(decimals);
        return n.format(value);
    }
    private boolean notificationPermissionGranted(){
        return Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    private void notifyTrades(){
        if(!notificationPermissionGranted())return; // Keep pending until Android permission is granted.
        NotificationManager manager=getSystemService(NotificationManager.class);
        JSONArray pending=engine.pendingTradeNotifications();
        for(int i=0;i<pending.length();i++){
            JSONObject trade=pending.optJSONObject(i);if(trade==null)continue;
            long seq=trade.optLong("seq",0);
            boolean buy="COMPRA".equals(trade.optString("type"));
            String channel=buy?BUY_CHANNEL:SELL_CHANNEL;
            NotificationChannel config=manager.getNotificationChannel(channel);
            if(config==null)break;
            if(config.getImportance()==NotificationManager.IMPORTANCE_NONE || !manager.areNotificationsEnabled()){
                engine.acknowledgeTradeNotification(seq); // Respect an explicit user-level mute.
                continue;
            }
            double units=trade.optDouble("btc"),amount=trade.optDouble("usdc"),price=trade.optDouble("price");
            double profit=trade.optDouble("profit"),cash=trade.optDouble("balanceCash"),btc=trade.optDouble("balanceBtc");
            String title=buy?"FluidGrid · COMPRA simulada":"FluidGrid · VENTA simulada";
            String shortLine=buy
                ?decimal(amount,2)+" USDC · BTC a "+decimal(price,2)
                :"Beneficio neto +"+decimal(profit,2)+" USDC · BTC a "+decimal(price,2);
            String message="Operación VIRTUAL · "+decimal(units,8)+" BTC a "+decimal(price,2)+" USD. "+
                (buy?"Invertido: "+decimal(amount,2)+" USDC. ":"Cobrado: "+decimal(amount,2)+" USDC. Beneficio neto: +"+decimal(profit,2)+" USDC. ")+
                "Saldo: "+decimal(cash,2)+" USDC y "+decimal(btc,8)+" BTC.";
            long eventTime=trade.optLong("t",0);
            if(eventTime>0 && System.currentTimeMillis()-eventTime>5*60_000L){
                message+=" Recuperada de velas anteriores (no ejecutada ahora).";
            }
            Intent open=new Intent(this,MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi=PendingIntent.getActivity(this,(int)(1000+seq%1000000),open,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this,channel)
                .setSmallIcon(buy?android.R.drawable.arrow_down_float:android.R.drawable.arrow_up_float)
                .setContentTitle(title).setContentText(shortLine)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true)
                .setContentIntent(pi).setWhen(eventTime>0?eventTime:System.currentTimeMillis())
                .setShowWhen(true).build();
            try{
                manager.notify((int)(1000+seq%1000000),n);
                engine.acknowledgeTradeNotification(seq); // Durable de-duplication across service restarts.
            }catch(SecurityException e){break;} // Permission revoked while posting; retry after authorization.
        }
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