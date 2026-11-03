package es.fluidgrid.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Spot paper-trading only: no API keys, no actual exchange orders.
 * SMC Lite zones are confirmed only after swingLength right-hand candles and ATR(50).
 */
public final class GridEngine {
    private static final long MINUTE = 60_000L, HOUR = 3_600_000L;
    private static final int PAGE_LIMIT = 300, MAX_CATCHUP_MINUTES = 10_080;
    private static final String STORAGE = "fluidgrid_native_v1", STATE = "state";
    private final SharedPreferences prefs;
    private final List<Zone> zones = new ArrayList<>();
    private long zonesRefreshed = 0L;
    private final Set<String> purchased = new HashSet<>(), sold = new HashSet<>();
    private final List<JSONObject> trades = new ArrayList<>();
    private JSONObject state;
    private final List<Bar> recentHourly = new ArrayList<>();

    private static final class Bar {
        long t; double o,h,l,c;
        Bar(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}
    }
    private static final class Zone {
        String id,type;double top,bottom;long born,expires=Long.MAX_VALUE;
        Zone(String id,String type,double top,double bottom,long born){this.id=id;this.type=type;this.top=top;this.bottom=bottom;this.born=born;}
        boolean active(long t){return born<=t && t<expires;}
        JSONObject json(){JSONObject x=new JSONObject();try{x.put("id",id);x.put("type",type);x.put("top",top);x.put("bottom",bottom);x.put("born",born);}catch(Exception ignored){}return x;}
    }
    public GridEngine(Context context){prefs=context.getSharedPreferences(STORAGE,Context.MODE_PRIVATE);load();}
    private synchronized void load(){
        try{state=new JSONObject(prefs.getString(STATE,""));}catch(Exception e){state=new JSONObject();}
        if(!state.has("initial")){
            try{state.put("initial",15000d).put("cash",15000d).put("btc",0d).put("cost",0d).put("injected",0d)
                 .put("realized",0d).put("fee",.001d).put("fraction",.1d).put("lastMinute",0L)
                 .put("lastPrice",0d).put("market","BTC-USDC").put("proxy",false)
                 .put("started",false).put("paused",false).put("blocked",false).put("status","Preparado");}
            catch(Exception ignored){}
        }
        JSONArray p=state.optJSONArray("purchased"),s=state.optJSONArray("sold"),l=state.optJSONArray("trades");
        if(p!=null)for(int i=0;i<p.length();i++)purchased.add(p.optString(i));
        if(s!=null)for(int i=0;i<s.length();i++)sold.add(s.optString(i));
        if(l!=null)for(int i=0;i<l.length();i++){JSONObject a=l.optJSONObject(i);if(a!=null)trades.add(a);}
        JSONArray storedHourly=state.optJSONArray("hourlyBarsCache");
        if(storedHourly!=null)for(int i=0;i<storedHourly.length();i++){
            JSONObject b=storedHourly.optJSONObject(i);if(b==null)continue;
            long t=b.optLong("t",0);double o=b.optDouble("o",0),h=b.optDouble("h",0),lowValue=b.optDouble("l",0),c=b.optDouble("c",0);
            if(t>0 && lowValue>0 && h>=Math.max(o,c) && lowValue<=Math.min(o,c))recentHourly.add(new Bar(t,o,h,lowValue,c));
        }
        JSONArray cache=state.optJSONArray("zonesCache");
        if(cache!=null)for(int i=0;i<cache.length();i++){JSONObject a=cache.optJSONObject(i);if(a==null)continue;
            Zone z=new Zone(a.optString("id"),a.optString("type"),a.optDouble("top"),a.optDouble("bottom"),a.optLong("born"));zones.add(z);
        }
    }
    private synchronized void save(){
        try{state.put("purchased",new JSONArray(purchased));state.put("sold",new JSONArray(sold));
            JSONArray cache=new JSONArray();long clock=System.currentTimeMillis();
            for(Zone z:zones)if(z.active(clock)&&z.expires==Long.MAX_VALUE)cache.put(z.json());
            state.put("zonesCache",cache).put("zonesCachedAt",clock);
            JSONArray hourlyCache=new JSONArray();
            for(Bar b:recentHourly){
                JSONObject j=new JSONObject();j.put("t",b.t).put("o",b.o).put("h",b.h).put("l",b.l).put("c",b.c);
                hourlyCache.put(j);
            }
            state.put("hourlyBarsCache",hourlyCache);
            JSONArray a=new JSONArray();for(JSONObject x:trades)a.put(x);state.put("trades",a);
            prefs.edit().putString(STATE,state.toString()).apply();}
        catch(Exception ignored){}
    }
    public synchronized JSONObject snapshot(){
        JSONObject copy;
        try{copy=new JSONObject(state.toString());}catch(Exception e){copy=new JSONObject();}
        JSONArray z=new JSONArray();long n=System.currentTimeMillis();
        if(n-state.optLong("zonesCachedAt",0)<2*HOUR)for(Zone q:zones)if(q.active(n) && q.expires==Long.MAX_VALUE)z.put(q.json());
        try{
            copy.put("zones",z);
            JSONArray hb=new JSONArray();
            for(Bar b:recentHourly){JSONObject j=new JSONObject();j.put("t",b.t).put("o",b.o).put("h",b.h).put("l",b.l).put("c",b.c);hb.put(j);}
            copy.put("hourlyBars",hb);
            double price=state.optDouble("lastPrice",0),btc=state.optDouble("btc",0),cash=state.optDouble("cash",0),cost=state.optDouble("cost",0),fee=state.optDouble("fee",.001);
            double equity=cash+(btc>0?btc*price*(1-fee):0);
            copy.put("equity",equity).put("unrealized",btc*price*(1-fee)-cost)
                .put("stale",state.optLong("lastUpdate",0)==0 || System.currentTimeMillis()-state.optLong("lastUpdate",0)>3*MINUTE);
            copy.put("average",btc>0?cost/btc:0);
            copy.put("target",btc>0?cost*1.01/(btc*(1-fee)):0);
            copy.put("advice",makeAdvice(price,cash,btc,cost,fee));
        }catch(Exception ignored){}
        return copy;
    }
    public synchronized void configure(double capital){
        if(state.optBoolean("started",false))throw new IllegalStateException("Ya iniciada. No puede restablecerse el capital.");
        if(!Double.isFinite(capital)||capital<=0||capital>1e9)throw new IllegalArgumentException("Capital fuera de rango");
        try{state.put("initial",capital).put("cash",capital);}catch(Exception ignored){}save();
    }
    public synchronized void start(){try{state.put("started",true).put("paused",false).put("status","Conectando a OKX…");}catch(Exception ignored){}save();}
    public synchronized void pause(){try{state.put("paused",true).put("status","Pausado a petición del usuario");}catch(Exception ignored){}save();}
    public synchronized void addCapital(double amount){
        if(!state.optBoolean("started"))throw new IllegalStateException("Inicia antes el simulador");
        if(!Double.isFinite(amount)||amount<1||amount>1e7)throw new IllegalArgumentException("Importe inválido");
        try{state.put("cash",state.optDouble("cash")+amount).put("injected",state.optDouble("injected")+amount);}catch(Exception ignored){}
        record(System.currentTimeMillis(),"APORTE",0,0,amount,0,"Aportación virtual autorizada, no beneficio");save();
    }
    private JSONObject makeAdvice(double price,double cash,double btc,double cost,double fee)throws JSONException{
        double base=state.optDouble("initial",15000)+state.optDouble("injected",0)+state.optDouble("realized",0);
        double tranche=base*state.optDouble("fraction",.1),suggested=Math.ceil(Math.max(0,2*tranche-cash)/10)*10;
        Zone nearest=null;
        for(Zone z:zones){if(!"demand".equals(z.type)||z.expires!=Long.MAX_VALUE)continue;
            if(price>0 && z.top<=price*1.03 && (nearest==null||Math.abs(z.top-price)<Math.abs(nearest.top-price)))nearest=z;
        }
        boolean insufficient=cash+1e-9<tranche, near=nearest!=null;
        String level="Mantener",reason="La reserva actual permite otra compra de 10 %; no hay necesidad operativa inmediata de aportar.";
        if(insufficient && near){
            level="Considerar aportación";
            reason="No queda efectivo para financiar un tramo de compra y existe una zona Demand confirmada próxima. Aportar recuperaría capacidad de compra, pero aumentaría la exposición a nuevas caídas.";
        }else if(insufficient){level="Vigilar";reason="El efectivo es insuficiente para otra compra del 10 %, pero no hay demanda confirmada próxima: aportar ahora no tiene una ventaja operativa demostrable.";}
        else if(cash<2*tranche){level="Vigilar";reason="Queda efectivo para una compra, pero no para dos; conviene conservar reserva sin adelantar nuevas aportaciones.";}
        double lowerTp=0,oldTp=btc>0?cost*1.01/(btc*(1-fee)):0;
        if(nearest!=null && btc>0 && nearest.top>0){
            double q=tranche*(1-fee)/nearest.top;
            lowerTp=(cost+tranche)*1.01/((btc+q)*(1-fee));
            if(lowerTp>=oldTp){reason+=" Una compra al precio de esa zona no reduciría el TP actual.";}
        }
        JSONObject x=new JSONObject();
        x.put("level",level).put("reason",reason).put("tranche",tranche).put("suggested",insufficient&&near?suggested:0)
         .put("nearestDemand",near?nearest.top:0).put("oldTp",oldTp).put("scenarioTp",lowerTp);
        return x;
    }
    private JSONObject record(long t,String type,double price,double btc,double usdc,double profit,String note){
        JSONObject x=new JSONObject();
        long seq=state.optLong("tradeSeq",0)+1;
        try{state.put("tradeSeq",seq);
            x.put("seq",seq).put("t",t).put("type",type).put("price",price).put("btc",btc)
                .put("usdc",usdc).put("profit",profit).put("note",note);
        }catch(Exception ignored){}
        trades.add(0,x);while(trades.size()>400)trades.remove(trades.size()-1);
        return x;
    }
    private void recordTrade(long t,String type,double price,double btc,double usdc,double profit,String note,
                             double cashAfter,double btcAfter){
        JSONObject x=record(t,type,price,btc,usdc,profit,note);
        try{x.put("balanceCash",cashAfter).put("balanceBtc",btcAfter);}catch(Exception ignored){}
    }
    /** Persistent notification outbox. Old v1.0 entries have no sequence and will not replay. */
    public synchronized JSONArray pendingTradeNotifications(){
        long delivered=state.optLong("lastNotifiedTradeSeq",0);
        JSONArray result=new JSONArray();
        for(int i=trades.size()-1;i>=0;i--){
            JSONObject t=trades.get(i);
            if(t.optLong("seq")>delivered &&
                ("COMPRA".equals(t.optString("type"))||"VENTA".equals(t.optString("type")))) result.put(t);
        }
        return result;
    }
    public synchronized void acknowledgeTradeNotification(long seq){
        if(seq>state.optLong("lastNotifiedTradeSeq",0) && seq<=state.optLong("tradeSeq",0)){
            try{state.put("lastNotifiedTradeSeq",seq);}catch(Exception ignored){}
            save();
        }
    }
    private List<Bar> getBars(String market,String interval,int maxPages,long oldestWanted)throws Exception{
        List<Bar> all=new ArrayList<>();long cursor=0;
        for(int p=0;p<maxPages;p++){
            String url="https://www.okx.com/api/v5/market/history-candles?instId="+market+"&bar="+interval+"&limit="+PAGE_LIMIT+(cursor>0?"&after="+cursor:"");
            JSONObject response=fetch(url);JSONArray data=response.optJSONArray("data");if(data==null||data.length()==0)break;
            long min=Long.MAX_VALUE;
            for(int j=0;j<data.length();j++){
                JSONArray a=data.optJSONArray(j);if(a==null||a.length()<6)continue;
                long t=Long.parseLong(a.getString(0));min=Math.min(min,t);
                if(a.length()>8 && !"1".equals(a.optString(8)))continue; // Only confirmed candles
                Bar b=new Bar(t,Double.parseDouble(a.getString(1)),Double.parseDouble(a.getString(2)),Double.parseDouble(a.getString(3)),Double.parseDouble(a.getString(4)));
                if(b.o>0&&b.h>=Math.max(b.o,b.c)&&b.l<=Math.min(b.o,b.c))all.add(b);
            }
            if(min==Long.MAX_VALUE||min>=cursor && cursor>0)break;
            cursor=min;
            if(min<=oldestWanted || data.length()<PAGE_LIMIT)break;
        }
        all.sort(Comparator.comparingLong(a->a.t));
        List<Bar> clean=new ArrayList<>();long prev=Long.MIN_VALUE;
        for(Bar b:all){if(b.t==prev)continue;clean.add(b);prev=b.t;}
        return clean;
    }
    private JSONObject fetch(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(9000);c.setReadTimeout(12000);c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","FluidGrid-paper-Android/1.0");
        try{int status=c.getResponseCode();if(status!=200)throw new IllegalStateException("HTTP "+status);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);
                JSONObject j=new JSONObject(b.toString());if(!"0".equals(j.optString("code")))throw new IllegalStateException("OKX: "+j.optString("msg"));return j;
            }
        }finally{c.disconnect();}
    }
    private String market()throws Exception{
        String chosen=state.optString("market","BTC-USDC");
        for(String m:new String[]{chosen,"BTC-USDC","BTC-USD"}){
            try{JSONArray a=fetch("https://www.okx.com/api/v5/market/ticker?instId="+m).getJSONArray("data");
                if(a.length()>0&&a.getJSONObject(0).optDouble("last")>0){
                    state.put("market",m).put("proxy",!m.equals("BTC-USDC"));return m;
                }
            }catch(Exception ignored){}
        }
        throw new IllegalStateException("No hay cotización pública accesible de BTC-USDC ni BTC-USD en OKX");
    }
    private void createZones(List<Bar> h){
        zones.clear();List<Zone> demand=new ArrayList<>(),supply=new ArrayList<>();double atr=0,initialTr=0;
        for(int i=0;i<h.size();i++){
            Bar b=h.get(i);double tr=i==0?b.h-b.l:Math.max(b.h-b.l,Math.max(Math.abs(b.h-h.get(i-1).c),Math.abs(b.l-h.get(i-1).c)));
            if(i<50){initialTr+=tr;if(i==49)atr=initialTr/50;}else atr=(atr*49+tr)/50;
            if(i>=49+20){
                int pivot=i-10;Bar a=h.get(pivot);boolean high=true,low=true;
                for(int j=pivot-10;j<=pivot+10;j++)if(j!=pivot){
                    if(h.get(j).h>a.h || (j>pivot && h.get(j).h==a.h))high=false;
                    if(h.get(j).l<a.l || (j>pivot && h.get(j).l==a.l))low=false;
                }
                if(high){
                    double top=a.h,bottom=top-.25*atr,poi=(top+bottom)/2;
                    if(!overlaps(supply,poi,atr,b.t+HOUR)){
                        Zone z=new Zone("S"+a.t,"supply",top,bottom,b.t+HOUR);zones.add(z);supply.add(0,z);
                        if(supply.size()>20){Zone old=supply.remove(supply.size()-1);old.expires=b.t+HOUR;}
                    }
                }else if(low){
                    double bottom=a.l,top=bottom+.25*atr,poi=(top+bottom)/2;
                    if(!overlaps(demand,poi,atr,b.t+HOUR)){
                        Zone z=new Zone("D"+a.t,"demand",top,bottom,b.t+HOUR);zones.add(z);demand.add(0,z);
                        if(demand.size()>20){Zone old=demand.remove(demand.size()-1);old.expires=b.t+HOUR;}
                    }
                }
            }
            // Break of structure only after the hour has actually closed.
            for(Zone z:supply)if(z.expires==Long.MAX_VALUE&&b.c>=z.top)z.expires=b.t+HOUR;
            for(Zone z:demand)if(z.expires==Long.MAX_VALUE&&b.c<=z.bottom)z.expires=b.t+HOUR;
            supply.removeIf(z->z.expires!=Long.MAX_VALUE);
            demand.removeIf(z->z.expires!=Long.MAX_VALUE);
        }
    }
    private boolean overlaps(List<Zone> existing,double poi,double atr,long time){
        for(Zone z:existing)if(z.active(time-1) && Math.abs((z.top+z.bottom)/2-poi)<=2*atr)return true;
        return false;
    }
    private synchronized void process(Bar m){
        double fee=state.optDouble("fee",.001),btc=state.optDouble("btc",0),cost=state.optDouble("cost",0),cash=state.optDouble("cash",0);
        // Intravela OHLC does not establish order: sales use pre-existing BTC; new BTC cannot be sold in this same minute.
        for(Zone z:zones){
            if(!"supply".equals(z.type)||!z.active(m.t)||sold.contains(z.id)||m.l>z.top||m.h<z.bottom||btc<1e-10)continue;
            double q=btc*.1,allocated=cost*(q/btc),proceeds=q*z.bottom*(1-fee),profit=proceeds-allocated;
            if(profit+1e-8<allocated*.01)continue;
            btc-=q;cost-=allocated;cash+=proceeds;sold.add(z.id);
            try{state.put("realized",state.optDouble("realized",0)+profit);}catch(Exception ignored){}
            recordTrade(m.t+MINUTE,"VENTA",z.bottom,q,proceeds,profit,"Supply "+z.id+" · TP neto ≥ 1 %",cash,btc);
        }
        double base=state.optDouble("initial",15000)+state.optDouble("injected",0)+state.optDouble("realized",0);
        double amount=base*state.optDouble("fraction",.1);
        for(Zone z:zones){
            if(!"demand".equals(z.type)||!z.active(m.t)||purchased.contains(z.id)||m.l>z.top||m.h<z.bottom)continue;
            if(cash+1e-7<amount)continue;
            double q=amount*(1-fee)/z.top;btc+=q;cost+=amount;cash-=amount;purchased.add(z.id);
            recordTrade(m.t+MINUTE,"COMPRA",z.top,q,amount,0,"Demand "+z.id+" · 10 % base del bot",cash,btc);
        }
        try{state.put("btc",Math.max(0,btc)).put("cost",Math.max(0,cost)).put("cash",Math.max(0,cash)).put("lastMinute",m.t);}catch(Exception ignored){}
    }
    public synchronized void tick()throws Exception{
        if(!state.optBoolean("started")||state.optBoolean("paused")||state.optBoolean("blocked"))return;
        try{
            String m=market();
            JSONObject ticker=fetch("https://www.okx.com/api/v5/market/ticker?instId="+m).getJSONArray("data").getJSONObject(0);
            double px=ticker.optDouble("last");if(!(px>0))throw new IllegalStateException("Cotización sin valor");
            state.put("lastPrice",px).put("lastUpdate",System.currentTimeMillis());
            if(zones.isEmpty()||System.currentTimeMillis()-zonesRefreshed>10*MINUTE){
                List<Bar> hourly=getBars(m,"1H",5,0);
                if(hourly.size()<100)throw new IllegalStateException("Faltan velas horarias suficientes para calcular zonas");
                for(int i=1;i<hourly.size();i++)if(hourly.get(i).t-hourly.get(i-1).t!=HOUR)
                    throw new IllegalStateException("Faltan horas en el histórico. Se bloquea el cálculo para evitar zonas incorrectas.");
                createZones(hourly);zonesRefreshed=System.currentTimeMillis();
                recentHourly.clear();
                int from=Math.max(0,hourly.size()-120);
                for(int k=from;k<hourly.size();k++)recentHourly.add(hourly.get(k));
            }
            long last=state.optLong("lastMinute",0),wanted=last==0?System.currentTimeMillis()-20*MINUTE:last;
            if(last>0 && System.currentTimeMillis()-last>MAX_CATCHUP_MINUTES*MINUTE){
                state.put("blocked",true).put("status","Pausa segura: más de 7 días sin datos. Revisar manualmente.");save();return;
            }
            List<Bar> mins=getBars(m,"1m",Math.max(1,(int)Math.min(35,Math.ceil((System.currentTimeMillis()-wanted)/(300d*MINUTE))+2)),wanted);
            if(mins.size()==0)throw new IllegalStateException("No se recibieron velas cerradas de 1 minuto");
            if(last==0){state.put("lastMinute",mins.get(mins.size()-1).t).put("status","En marcha · desde ahora");save();return;}
            long expected=last+MINUTE;int n=0;
            for(Bar b:mins){if(b.t<=last)continue;
                if(b.t!=expected){state.put("blocked",true).put("status","Pausa segura: faltan velas desde "+expected);save();return;}
                process(b);expected+=MINUTE;n++;
            }
            if(n==0 && System.currentTimeMillis()-last>3*MINUTE){
                state.put("status","Sin velas recientes · esperando a OKX");
            }else state.put("status",n>0?"Actualizado · "+n+" minutos procesados":"Sin nuevo minuto cerrado");
            save();
        }catch(Exception e){
            try{state.put("status","Sin datos verificables: "+e.getMessage());}catch(Exception ignored){}save();throw e;
        }
    }
    public synchronized void acknowledgeGap(){
        try{state.put("blocked",false).put("lastMinute",0).put("status","Hueco aceptado; nueva simulación desde ahora");}catch(Exception ignored){}save();
    }
    public synchronized String csv(){StringBuilder s=new StringBuilder("fecha;tipo;precio;btc;usdc;beneficio;detalle\n");
        for(JSONObject t:trades){s.append(t.optLong("t")).append(';').append(t.optString("type")).append(';').append(t.optDouble("price")).append(';')
            .append(t.optDouble("btc")).append(';').append(t.optDouble("usdc")).append(';').append(t.optDouble("profit")).append(';')
            .append(t.optString("note").replace(';',',').replace('\n',' ')).append('\n');}
        return s.toString();
    }
}