package es.fluidgrid.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.text.TextUtils;
import android.app.NotificationManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native Android dashboard, not an HTML shortcut. */
public final class MainActivity extends Activity {
    private static final int BG=0xFF07131E,CARD=0xFF102236,LINE=0xFF35506A,WHITE=0xFFF1F7FB,MUTED=0xFFA4BBD0,
        CYAN=0xFF69D8E0,GREEN=0xFF59D9A3,RED=0xFFFF888D,AMBER=0xFFFFCA75;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private ScrollView scroll;
    private ChartView chartView;
    private boolean showFarZones=false;
    private boolean active=false;
    private static final int REQUEST_BIN=1707;
    private final ExecutorService testExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelTest=new AtomicBoolean(false);
    private volatile boolean testRunning=false;
    private volatile int testPercent=0;
    private volatile long testRows=0;
    private String testFileName="";
    private JSONObject testReport;
    private ProgressBar testProgressView;
    private TextView testProgressLabel;
    private String updateState="No se ha comprobado todavía.";
    private String updateUrl="";
    private String latestVersion="";
    private boolean updateChecking=false;
    private final Runnable redraw=new Runnable(){@Override public void run(){if(active){if(chartView==null||!chartView.isInteracting())render();handler.postDelayed(this,20000);}}};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},501);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(18),dp(14),dp(40));
        showFarZones=getSharedPreferences("fluidgrid_ui",MODE_PRIVATE).getBoolean("farZones",false);
        chartView=new ChartView(this);chartView.setShowFarZones(showFarZones);
        scroll.addView(body);setContentView(scroll);
        try{testReport=new JSONObject(getSharedPreferences("fluidgrid_backtests",MODE_PRIVATE).getString("last_report","{}"));}catch(Exception ignored){}
        checkForUpdates();
    }
    @Override protected void onResume(){super.onResume();active=true;handler.removeCallbacks(redraw);redraw.run();}
    @Override protected void onPause(){active=false;handler.removeCallbacks(redraw);super.onPause();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout panel(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(15),dp(15),dp(15));
        GradientDrawable d=new GradientDrawable();d.setColor(CARD);d.setCornerRadius(dp(17));d.setStroke(dp(1),LINE);c.setBackground(d);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(11));body.addView(c,p);return c;
    }
    private void add(LinearLayout parent,String value,int size,int color,boolean bold){
        TextView t=text(value,size,color,bold);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(6);parent.addView(t,p);
    }
    private Button button(String label,int color,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(WHITE);
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));b.setBackground(d);
        b.setOnClickListener(v->action.run());return b;
    }
    private String num(double x,int decimals){NumberFormat f=NumberFormat.getNumberInstance(new Locale("es","ES"));f.setMinimumFractionDigits(decimals);f.setMaximumFractionDigits(decimals);return f.format(x);}
    private String money(double x){return num(x,2)+" USDC";}
    private String date(long millis){return millis==0?"—":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.getDefault()).format(new Date(millis));}
    private String versionName(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "1.x";}
    }
    private void render(){
        JSONObject s=new GridEngine(this).snapshot();
        final int priorScroll=scroll.getScrollY();
        // Retain the ChartView instance: panning and zoom must survive screen refreshes.
        if(chartView.getParent() instanceof ViewGroup)((ViewGroup)chartView.getParent()).removeView(chartView);
        body.removeAllViews();
        add(body,"◈  FluidGrid",26,WHITE,true);
        add(body,"Versión "+versionName()+" · BTC spot · FluidTrades SMC Lite · 1 hora · simulación",11,MUTED,false);
        LinearLayout hero=panel();
        String market=s.optString("market","BTC-USDC");add(hero,"Fuente OKX: "+market+(s.optBoolean("proxy")?" · libro unificado USD/USDC": ""),13,CYAN,true);
        add(hero,"Para comparar zonas, TradingView debe utilizar datos de OKX y los mismos ajustes (5/20/5).",11,MUTED,false);
        add(hero,s.optDouble("lastPrice")>0?num(s.optDouble("lastPrice"),2)+" USD":"Sin cotización verificada",27,WHITE,true);
        add(hero,s.optString("status","Preparado"),12,s.optBoolean("blocked")||s.optBoolean("stale")?AMBER:MUTED,false);
        if(s.optBoolean("stale")&&s.optBoolean("started"))add(hero,"Aviso: sin cotización reciente verificada. Revisa si Android ha suspendido la supervisión.",11,RED,true);
        add(hero,"Última actualización: "+date(s.optLong("lastUpdate")),11,MUTED,false);
        long now=System.currentTimeMillis();
        long lastUp=s.optLong("lastUpdate",0);
        boolean running=s.optBoolean("started") && !s.optBoolean("paused") && !s.optBoolean("blocked");
        int elapsed=lastUp>0?(int)Math.max(0,Math.min(60,(now-lastUp)/1000)):0;
        int remaining=lastUp>0?Math.max(0,60-elapsed):60;
        add(hero,running?"Próxima actualización estimada en ~"+remaining+" s":"Cuenta atrás detenida",11,AMBER,false);
        ProgressBar pb=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        pb.setMax(60);pb.setProgress(running?elapsed:0);
        LinearLayout.LayoutParams pbar=new LinearLayout.LayoutParams(-1,dp(10));pbar.bottomMargin=dp(10);
        hero.addView(pb,pbar);
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        boolean started=s.optBoolean("started"),paused=s.optBoolean("paused");
        Button play=button(started&&!paused?"En marcha":"▶ Iniciar / reanudar",0xFF187A8C,()->start());
        Button stop=button("⏸ Pausar",0xFF51425F,()->pause());
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(48),1);half.setMargins(0,0,dp(5),0);buttons.addView(play,half);buttons.addView(stop,new LinearLayout.LayoutParams(0,dp(48),1));hero.addView(buttons);
        if(!started){Button setup=button("Configurar capital inicial",0xFF25517B,()->capitalDialog(s));hero.addView(setup);}
        LinearLayout updates=panel();add(updates,"VERSIÓN Y ACTUALIZACIONES",12,CYAN,true);
        metric(updates,"Versión instalada",versionName(),WHITE);
        add(updates,updateChecking?"Consultando versiones publicadas en GitHub…":updateState,12,updateUrl.isEmpty()?MUTED:GREEN,false);
        updates.addView(button(updateChecking?"Comprobando…":"Buscar actualizaciones",0xFF245277,()->checkForUpdates()));
        if(!updateUrl.isEmpty())updates.addView(button("Descargar FluidGrid "+latestVersion,0xFF176F65,()->openDownload()));
        add(updates,"La descarga se realiza desde GitHub y Android debe autorizar la instalación. La actualización sin desinstalar requiere conservar el mismo identificador y certificado de firma.",10,MUTED,false);
        if(s.optBoolean("blocked")){
            add(hero,"Se ha detectado un hueco de mercado. No se simulan operaciones durante datos no verificables.",12,RED,true);
            hero.addView(button("Aceptar hueco y retomar desde ahora",0xFF694F21,()->confirmGap()));
        }
        LinearLayout alerts=panel();add(alerts,"AVISOS DE COMPRA Y VENTA",12,CYAN,true);
        NotificationManager nm=getSystemService(NotificationManager.class);
        boolean permission=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        add(alerts,permission && nm.areNotificationsEnabled()?"Notificaciones permitidas":"Avisos desactivados: autoriza las notificaciones",
            13,permission && nm.areNotificationsEnabled()?GREEN:AMBER,true);
        add(alerts,"Cada COMPRA y VENTA simulada genera un aviso individual, con precio, importe, BTC, saldo USDC y beneficio neto de la venta. Las operaciones recuperadas aparecen fechadas como históricas.",11,MUTED,false);
        alerts.addView(button("Configurar notificaciones",0xFF25517B,()->{
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},501);
            } else {
                Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());startActivity(i);
            }
        }));
        LinearLayout metrics=panel();add(metrics,"PATRIMONIO Y BALANCES",12,CYAN,true);
        metric(metrics,"Patrimonio liquidable",money(s.optDouble("equity")),WHITE);
        metric(metrics,"USDC disponibles",money(s.optDouble("cash")),CYAN);
        metric(metrics,"BTC en cartera",num(s.optDouble("btc"),8)+" BTC",WHITE);
        metric(metrics,"Coste BTC pendientes",money(s.optDouble("cost")),WHITE);
        metric(metrics,"Beneficio realizado",money(s.optDouble("realized")),GREEN);
        double latent=s.optDouble("unrealized");metric(metrics,"Resultado latente",money(latent),latent>=0?GREEN:RED);
        metric(metrics,"Precio medio efectivo",s.optDouble("average")>0?num(s.optDouble("average"),2)+" USD":"—",WHITE);
        metric(metrics,"Precio mínimo TP neto 1 %",s.optDouble("target")>0?num(s.optDouble("target"),2)+" USD":"—",AMBER);
        metric(metrics,"Aportaciones extraordinarias",money(s.optDouble("injected")),WHITE);
        metrics.addView(button("+ Inyectar capital virtual",0xFF176F65,()->addDialog(s)));

        JSONObject advice=s.optJSONObject("advice");LinearLayout a=panel();add(a,"ASESOR DE LIQUIDEZ",12,CYAN,true);
        if(advice!=null){
            String level=advice.optString("level");add(a,level,19,level.equals("Considerar aportación")?AMBER:GREEN,true);
            add(a,advice.optString("reason"),13,WHITE,false);
            metric(a,"Tramo de compra (10 %)",money(advice.optDouble("tranche")),WHITE);
            if(advice.optDouble("suggested")>0)metric(a,"Aportación virtual sugerida",money(advice.optDouble("suggested")),AMBER);
            if(advice.optDouble("nearestDemand")>0)metric(a,"Demanda más cercana",num(advice.optDouble("nearestDemand"),2)+" USD",CYAN);
            if(advice.optDouble("scenarioTp")>0)metric(a,"TP si se compra otro tramo",num(advice.optDouble("scenarioTp"),2)+" USD",WHITE);
            add(a,"El consejo sigue reglas de liquidez. No predice el precio, ni compromete aportaciones reales.",10,MUTED,false);
        }
        LinearLayout chart=panel();add(chart,"BTC/USDC · 1H · FLUIDTRADES",13,CYAN,true);
        add(chart,"5/20/5 · ATR 50 · zonas activas confirmadas de OKX",11,MUTED,false);
        add(chart,"Arrastra con un dedo · Pellizca con dos · Doble toque para restablecer",10,MUTED,false);
        chartView.setShowFarZones(showFarZones);
        chartView.setData(s.optJSONArray("hourlyBars"),s.optJSONArray("zones"),s.optJSONArray("trades"),s.optDouble("lastPrice"));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(355));cp.bottomMargin=dp(5);
        chart.addView(chartView,cp);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle=button(showFarZones?"Ocultar zonas lejanas":"Mostrar zonas lejanas",0xFF245277,()->{
            showFarZones=!showFarZones;
            getSharedPreferences("fluidgrid_ui",MODE_PRIVATE).edit().putBoolean("farZones",showFarZones).apply();
            chartView.setShowFarZones(showFarZones);render();
        });
        Button reset=button("Restablecer",0xFF30425A,()->{chartView.resetView();});
        LinearLayout.LayoutParams p1=new LinearLayout.LayoutParams(0,dp(43),2);p1.setMargins(0,0,dp(7),0);
        row.addView(toggle,p1);row.addView(reset,new LinearLayout.LayoutParams(0,dp(43),1));chart.addView(row);
        LinearLayout zoomRow=new LinearLayout(this);zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        Button smaller=button("− Alejar",0xFF293E58,()->chartView.zoomOut());
        Button larger=button("+ Acercar",0xFF1B6177,()->chartView.zoomIn());
        Button recent=button("Ir a ahora",0xFF176F65,()->chartView.resetView());
        LinearLayout.LayoutParams controlA=new LinearLayout.LayoutParams(0,dp(45),1);
        controlA.setMargins(0,dp(7),dp(5),0);
        LinearLayout.LayoutParams controlB=new LinearLayout.LayoutParams(0,dp(45),1);
        controlB.setMargins(0,dp(7),dp(5),0);
        LinearLayout.LayoutParams controlC=new LinearLayout.LayoutParams(0,dp(45),1);
        controlC.setMargins(0,dp(7),0,0);
        zoomRow.addView(smaller,controlA);zoomRow.addView(larger,controlB);zoomRow.addView(recent,controlC);
        chart.addView(zoomRow);
        add(chart,"Un dedo dentro del gráfico: desplazar velas. Dos dedos: zoom. Para desplazarte por la pantalla, arrastra fuera del gráfico.",10,MUTED,false);
        add(chart,"▲ Compra simulada      ▼ Venta simulada      ┄ Precio actual",10,MUTED,false);

        LinearLayout tester=panel();add(tester,"TESTADOR HISTÓRICO · ARCHIVO BIN",12,CYAN,true);
        add(tester,"Selecciona un BIN BTCUSD de 1 minuto. Reproducirá FluidTrades 5/20/5 en BTC 1h sin alterar la cartera virtual activa.",11,MUTED,false);
        tester.addView(button(testRunning?"Test en ejecución":"Elegir BIN y ejecutar test",0xFF196C80,()->chooseBin()));
        if(testRunning)tester.addView(button("Cancelar test",0xFF69415A,()->cancelTest.set(true)));
        testProgressView=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        testProgressView.setMax(100);testProgressView.setProgress(testPercent);tester.addView(testProgressView,new LinearLayout.LayoutParams(-1,dp(8)));
        testProgressLabel=text(testRunning?"Procesando "+num(testRows,0)+" velas · "+(testPercent<0?"Progreso desconocido":testPercent+" %"):(testFileName.isEmpty()?"Sin prueba ejecutándose":testFileName),11,MUTED,false);
        tester.addView(testProgressLabel);
        if(testReport!=null && testReport.optLong("records")>0){
            add(tester,"RESULTADO DEL ÚLTIMO TEST",12,CYAN,true);
            add(tester,"Periodo: "+dayUTC(testReport.optLong("start"))+" – "+dayUTC(testReport.optLong("end"))+" · "+num(testReport.optDouble("records"),0)+" velas",11,MUTED,false);
            metric(tester,"Patrimonio liquidable final",money(testReport.optDouble("equity")),WHITE);
            metric(tester,"Beneficio neto de la cartera",money(testReport.optDouble("profitNet")),testReport.optDouble("profitNet")>=0?GREEN:RED);
            metric(tester,"Beneficios realizados",money(testReport.optDouble("realized")),GREEN);
            metric(tester,"Resultado latente",money(testReport.optDouble("unrealized")),testReport.optDouble("unrealized")>=0?GREEN:RED);
            metric(tester,"USDC disponibles",money(testReport.optDouble("cash")),CYAN);
            metric(tester,"BTC acumulados",num(testReport.optDouble("btc"),8)+" BTC",WHITE);
            metric(tester,"Compras / ventas simuladas",testReport.optInt("buyCount")+" / "+testReport.optInt("sellCount"),WHITE);
            metric(tester,"Meses con ganancias realizadas",testReport.optInt("positiveMonths")+" de "+testReport.optInt("monthsCount"),WHITE);
            metric(tester,"Mayor racha sin beneficios",testReport.optInt("maxDryMonths")+" meses",AMBER);
            metric(tester,"Drawdown máximo (cierres de minuto)",num(testReport.optDouble("maxDrawdown"),2)+" %",RED);
            metric(tester,"Comisiones totales estimadas",money(testReport.optDouble("fees")),MUTED);
            add(tester,"RESULTADOS MENSUALES · últimos 12 meses",11,CYAN,true);
            JSONArray m=testReport.optJSONArray("monthly");
            if(m!=null)for(int i=Math.max(0,m.length()-12);i<m.length();i++){
                JSONObject q=m.optJSONObject(i);if(q==null)continue;
                double profit=q.optDouble("profit");add(tester,q.optString("month")+" · "+money(profit)+" · "+q.optInt("sells")+" ventas",11,profit>0?GREEN:MUTED,false);
            }
            tester.addView(button("Compartir todos los meses (CSV)",0xFF30425A,()->share(BinBacktester.monthlyCsv(testReport))));
            add(tester,"El BIN representa BTC/USD, no necesariamente precios ejecutables BTC/USDC. El test aplica comisiones del 0,10 % por lado, sin deslizamiento ni aportaciones. Solo confirma zonas después de disponer de las velas necesarias.",10,MUTED,false);
        }
        LinearLayout z=panel();add(z,"ZONAS ACTIVAS · 1H · 5/20/5",12,CYAN,true);
        JSONArray zones=s.optJSONArray("zones");int count=0;
        if(zones!=null)for(int i=0;i<zones.length();i++){
            JSONObject q=zones.optJSONObject(i);if(q==null)continue;count++;
            add(z,(q.optString("type").equals("demand")?"🟢 DEMAND · ":"🔴 SUPPLY · ")+num(q.optDouble("bottom"),2)+"–"+num(q.optDouble("top"),2),12,WHITE,true);
        }
        if(count==0)add(z,"No hay zonas verificadas todavía. Comprueba la conexión.",12,MUTED,false);
        LinearLayout log=panel();add(log,"ÚLTIMAS OPERACIONES",12,CYAN,true);
        JSONArray trades=s.optJSONArray("trades");
        if(trades==null||trades.length()==0)add(log,"Todavía no se han simulado operaciones.",12,MUTED,false);
        else for(int i=0;i<Math.min(12,trades.length());i++){
            JSONObject t=trades.optJSONObject(i);if(t==null)continue;String type=t.optString("type");
            add(log,date(t.optLong("t"))+" · "+type+" · "+money(t.optDouble("usdc"))+("VENTA".equals(type)?" · +"+money(t.optDouble("profit")):""),11,
                "VENTA".equals(type)?GREEN:"COMPRA".equals(type)?CYAN:AMBER,true);
        }
        log.addView(button("Compartir CSV de operaciones",0xFF2D4763,()->share(new GridEngine(this).csv())));
        LinearLayout note=panel();
        add(note,"AVISO DE FUNCIONAMIENTO",12,AMBER,true);
        add(note,"Las compras y ventas son simuladas y usan velas de 1 minuto cerradas. La ejecución a precio de zona es una aproximación OHLC, no una orden real. Android puede suspender la supervisión; si falta histórico, se pausa para no inventar operaciones. No garantiza servicio continuo 24/7.",11,MUTED,false);
        add(note,"Capital virtual inicial: "+money(s.optDouble("initial"))+" · Comisión por lado: 0,10 % · Sin apalancamiento",11,MUTED,false);
        scroll.post(()->scroll.scrollTo(0,priorScroll));
    }
    private void metric(LinearLayout parent,String title,String value,int col){
        TextView t=text(title,11,MUTED,false);parent.addView(t);
        TextView v=text(value,18,col,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(11);parent.addView(v,p);
    }
    private void start(){Intent i=new Intent(this,MarketService.class);i.setAction(MarketService.START);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception e){toast(e.getMessage());}render();}
    private void pause(){Intent i=new Intent(this,MarketService.class);i.setAction(MarketService.PAUSE);startService(i);render();}
    private void confirmGap(){new AlertDialog.Builder(this).setTitle("Saltar un hueco no verificado")
        .setMessage("Se retomará desde el minuto actual, sin atribuir operaciones al intervalo perdido. ¿Confirmas?")
        .setPositiveButton("Confirmar",(d,w)->{Intent i=new Intent(this,MarketService.class);i.setAction(MarketService.GAP);startService(i);})
        .setNegativeButton("Cancelar",null).show();}
    private void capitalDialog(JSONObject s){inputDialog("Capital inicial virtual","15000",n->{
        try{new GridEngine(this).configure(n);render();}catch(Exception e){toast(e.getMessage());}
    });}
    private void addDialog(JSONObject s){
        JSONObject advice=s.optJSONObject("advice");double val=advice==null?300:advice.optDouble("suggested",300);
        inputDialog("Aportación virtual (requiere autorización)",num(val>0?val:300,0).replace(".",""),n->{
            new AlertDialog.Builder(this).setTitle("Confirmar aportación SIMULADA")
                .setMessage("Añadir "+money(n)+" al saldo virtual de USDC. No realiza ningún cargo ni compra real.")
                .setPositiveButton("Autorizar",(d,w)->{Intent i=new Intent(this,MarketService.class);i.setAction(MarketService.ADD);i.putExtra("amount",n);
                    startService(i);})
                .setNegativeButton("Cancelar",null).show();
        });
    }
    private interface AmountCallback{void accept(double amount);}
    private void inputDialog(String title,String initial,AmountCallback cb){
        EditText edit=new EditText(this);edit.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setText(initial);edit.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(title).setView(edit)
            .setPositiveButton("Continuar",(d,w)->{try{double n=Double.parseDouble(edit.getText().toString().replace(',','.').trim());
                if(n<=0||!Double.isFinite(n))throw new IllegalArgumentException();cb.accept(n);
            }catch(Exception e){toast("Introduce un número positivo válido");}})
            .setNegativeButton("Cancelar",null).show();
    }
    private void share(String data){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,data);
        i.putExtra(Intent.EXTRA_SUBJECT,"FluidGrid · operaciones CSV");startActivity(Intent.createChooser(i,"Compartir historial"));}

    private String dayUTC(long seconds){
        SimpleDateFormat f=new SimpleDateFormat("dd/MM/yyyy",new Locale("es","ES"));
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return seconds>0?f.format(new Date(seconds*1000)):"—";
    }
    private void chooseBin(){
        if(testRunning){toast("Hay un test en curso");return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try{startActivityForResult(i,REQUEST_BIN);}catch(Exception e){toast("No se pudo abrir el selector de archivos");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_BIN||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String name="histórico.bin";long size=-1;
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int a=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),b=cursor.getColumnIndex(OpenableColumns.SIZE);
                if(a>=0)name=cursor.getString(a);if(b>=0&&!cursor.isNull(b))size=cursor.getLong(b);
            }
        }catch(Exception ignored){}
        if(!name.toLowerCase(Locale.ROOT).endsWith(".bin")){toast("Selecciona un archivo .bin compatible");return;}
        final String selectedName=name;
        final long selectedSize=size;
        inputDialog("Capital virtual para el backtest",num(new GridEngine(this).snapshot().optDouble("initial",15000),0).replace(".",""),n->runBacktest(uri,selectedName,selectedSize,n));
    }
    private void runBacktest(Uri uri,String name,long size,double capital){
        if(testRunning)return;
        testRunning=true;cancelTest.set(false);testPercent=0;testRows=0;
        testFileName=name+" · ejecutando";
        render();
        testExecutor.execute(()->{
            try{
                BinBacktester engine=new BinBacktester(capital);
                JSONObject result=engine.run(getContentResolver(),uri,size,(percent,rows)->runOnUiThread(()->{
                    if(!testRunning)return;
                    testPercent=percent;testRows=rows;
                    if(testProgressView!=null){testProgressView.setIndeterminate(percent<0);if(percent>=0)testProgressView.setProgress(percent);}
                    if(testProgressLabel!=null)testProgressLabel.setText(name+" · "+num(rows,0)+" velas · "+(percent<0?"leyendo":percent+" %"));
                }),cancelTest);
                runOnUiThread(()->{
                    testRunning=false;testPercent=100;testReport=result;testFileName=name+" · finalizado";
                    getSharedPreferences("fluidgrid_backtests",MODE_PRIVATE).edit().putString("last_report",result.toString()).apply();
                    render();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    testRunning=false;testFileName=cancelTest.get()?"Test cancelado":"Error en "+name+": "+e.getMessage();
                    toast(testFileName);render();
                });
            }
        });
    }
    private void checkForUpdates(){
        if(updateChecking)return;
        updateChecking=true;updateState="Comprobando actualizaciones…";
        if(active)render();
        UpdateChecker.check(this,result->{
            updateChecking=false;updateState=result.message;
            updateUrl=result.newer?result.downloadUrl:"";
            latestVersion=result.newer?result.version:"";
            if(active)render();
        });
    }
    private void openDownload(){
        if(!updateUrl.startsWith("https://github.com/lazgom-hash/miBot-BTCEUR-Android/releases/download/"))return;
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(updateUrl)));}catch(Exception e){toast("No se pudo abrir GitHub en el navegador");}
    }
    @Override protected void onDestroy(){
        cancelTest.set(true);testExecutor.shutdownNow();handler.removeCallbacks(redraw);super.onDestroy();
    }
    private void toast(String t){Toast.makeText(this,t,Toast.LENGTH_LONG).show();}
}