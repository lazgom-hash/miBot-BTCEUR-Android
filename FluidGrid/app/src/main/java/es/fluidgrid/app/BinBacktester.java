package es.fluidgrid.app;

import android.content.ContentResolver;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline/backwards-safe simulation of the FluidTrades 5/20/5, ATR(50) strategy.
 * Expected BIN: little-endian 20B/record: unsigned Unix seconds + float32 O/H/L/C.
 * The simulation works on historical BTCUSD candles as a USD proxy for BTC/USDC,
 * and uses no real orders, no indicators beyond ATR used to define FluidTrades zones.
 * One-pass streaming: no need to load 75MB into phone memory.
 */
public final class BinBacktester {
    public interface Listener { void progress(int percent, long records); }
    public static final int RECORD_BYTES=20;
    private static final long HOUR=3600L;
    private static final double FEE=.001, TP=.01, TRANCHE=.10, BOX_WIDTH=.5;
    private static final int SWING=5, ATR_PERIODS=50, HISTORY=20;

    static final class Bar {
        final long t;
        double o,h,l,c;
        Bar(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}
        void update(double h,double l,double c){this.h=Math.max(this.h,h);this.l=Math.min(this.l,l);this.c=c;}
    }
    static final class Zone {
        final long id,born; final boolean demand; final double top,bottom;
        boolean bought,sold;
        Zone(long id,long born,boolean demand,double top,double bottom){
            this.id=id;this.born=born;this.demand=demand;this.top=top;this.bottom=bottom;
        }
    }
    static final class Month {
        final int key;
        double profit;int buys,sells;
        Month(int key){this.key=key;}
        JSONObject json() throws Exception {
            JSONObject a=new JSONObject();a.put("month",String.format(Locale.US,"%04d-%02d",key/12,(key%12)+1));
            a.put("profit",profit).put("buys",buys).put("sells",sells);return a;
        }
    }

    private final List<Bar> hours=new ArrayList<>();
    private final List<Zone> demand=new ArrayList<>(),supply=new ArrayList<>();
    private final TreeMap<Integer,Month> monthly=new TreeMap<>();
    private transient Month currentMonth;
    private long nextMonthStart=0;
    private double cash,btc,cost,realized,lastClose,atr,initialTR,peak=0,maxDrawdown=0,fees=0;
    private int buyCount=0,sellCount=0;
    private long first=-1,last=-1,rows=0;
    private final double initial;

    public BinBacktester(double capital){
        if(!Double.isFinite(capital)||capital<100||capital>1_000_000_000)throw new IllegalArgumentException("Capital inicial inválido");
        initial=capital;cash=capital;peak=capital;
    }
    private static int monthKey(long unixSeconds){
        java.util.Calendar c=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"),Locale.US);
        c.setTimeInMillis(unixSeconds*1000);
        return c.get(java.util.Calendar.YEAR)*12+c.get(java.util.Calendar.MONTH);
    }
    private Month month(long t){
        if(currentMonth!=null && t<nextMonthStart)return currentMonth;
        java.util.Calendar calendar=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"),Locale.US);
        calendar.setTimeInMillis(t*1000);
        int year=calendar.get(java.util.Calendar.YEAR),month=calendar.get(java.util.Calendar.MONTH);
        int key=year*12+month;
        currentMonth=monthly.get(key);
        if(currentMonth==null){currentMonth=new Month(key);monthly.put(key,currentMonth);}
        calendar.set(java.util.Calendar.DAY_OF_MONTH,1);
        calendar.set(java.util.Calendar.HOUR_OF_DAY,0);
        calendar.set(java.util.Calendar.MINUTE,0);
        calendar.set(java.util.Calendar.SECOND,0);
        calendar.set(java.util.Calendar.MILLISECOND,0);
        calendar.add(java.util.Calendar.MONTH,1);
        nextMonthStart=calendar.getTimeInMillis()/1000;
        return currentMonth;
    }
    private static boolean touches(double low,double high,Zone z){return high>=z.bottom&&low<=z.top;}
    private void minute(long t,double high,double low,double close){
        if(btc>1e-12){
            for(Zone z:supply){
                if(z.sold||!touches(low,high,z))continue;
                double q=btc*TRANCHE;
                double allocated=cost*(q/btc);
                double received=q*z.bottom*(1-FEE);
                double profit=received-allocated;
                if(profit+1e-8<allocated*TP)continue;
                btc-=q;cost-=allocated;cash+=received;realized+=profit;fees+=q*z.bottom*FEE;
                z.sold=true;sellCount++;month(t).profit+=profit;month(t).sells++;
            }
        }
        double amount=(initial+realized)*TRANCHE;
        for(Zone z:demand){
            if(z.bought||!touches(low,high,z)||cash+1e-8<amount)continue;
            double acquired=(amount*(1-FEE))/z.top;
            btc+=acquired;cost+=amount;cash-=amount;fees+=amount*FEE;
            z.bought=true;buyCount++;month(t).buys++;
        }
        lastClose=close;
        // Drawdown measured using one-minute CLOSING prices (not extreme intrabar prices).
        double equity=cash+btc*close*(1-FEE);
        peak=Math.max(peak,equity);
        if(peak>0)maxDrawdown=Math.max(maxDrawdown,100*(peak-equity)/peak);
    }
    private static double tr(Bar b,Bar previous){
        if(previous==null)return b.h-b.l;
        return Math.max(b.h-b.l,Math.max(Math.abs(b.h-previous.c),Math.abs(b.l-previous.c)));
    }
    private static boolean overlaps(List<Zone> list,double poi,double atr){
        for(Zone z:list){
            double other=(z.top+z.bottom)*.5;
            if(Math.abs(poi-other)<=2*atr)return true;
        }
        return false;
    }
    private static void prune(List<Zone> zones){
        while(zones.size()>HISTORY)zones.remove(zones.size()-1);
    }
    /** Confirm 1h pivot only after the closing hour, then create zone for the next hour. */
    private void completeHour(Bar bar){
        Bar prev=hours.isEmpty()?null:hours.get(hours.size()-1);
        double trueRange=tr(bar,prev);
        int i=hours.size();
        if(i<ATR_PERIODS){initialTR+=trueRange;if(i==ATR_PERIODS-1)atr=initialTR/ATR_PERIODS;}
        else atr=(atr*(ATR_PERIODS-1)+trueRange)/ATR_PERIODS;
        hours.add(bar);
        if(i>=ATR_PERIODS-1&&i>=SWING*2){
            int pivot=i-SWING;Bar middle=hours.get(pivot);
            boolean high=true,low=true;
            for(int j=pivot-SWING;j<=pivot+SWING;j++){
                if(j==pivot)continue;
                Bar candidate=hours.get(j);
                if(candidate.h>middle.h||(j>pivot&&candidate.h==middle.h))high=false;
                if(candidate.l<middle.l||(j>pivot&&candidate.l==middle.l))low=false;
            }
            long eligible=bar.t+HOUR;
            if(high){
                double top=middle.h,bottom=top-BOX_WIDTH*atr,poi=(top+bottom)*.5;
                if(!overlaps(supply,poi,atr)){
                    supply.add(0,new Zone(middle.t,eligible,false,top,bottom));prune(supply);
                }
            }else if(low){
                double bottom=middle.l,top=bottom+BOX_WIDTH*atr,poi=(top+bottom)*.5;
                if(!overlaps(demand,poi,atr)){
                    demand.add(0,new Zone(middle.t,eligible,true,top,bottom));prune(demand);
                }
            }
        }
        // The Pine code converts Supply/Demand to BOS on a closing-price break.
        // This happens only now, never before the closing hour becomes known.
        supply.removeIf(z->bar.c>=z.top);
        demand.removeIf(z->bar.c<=z.bottom);
    }

    public JSONObject run(ContentResolver resolver,Uri file,long sizeBytes,Listener cb,AtomicBoolean cancelled) throws Exception {
        if(sizeBytes>0 && (sizeBytes%RECORD_BYTES!=0||sizeBytes<RECORD_BYTES))
            throw new IOException("Formato BIN no compatible: el tamaño no es múltiplo de 20 bytes");
        long expectedRows=sizeBytes>0?sizeBytes/RECORD_BYTES:0;
        byte[] raw=new byte[RECORD_BYTES];
        ByteBuffer buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Bar current=null;
        long prev=-1;
        try(InputStream in=new BufferedInputStream(resolver.openInputStream(file),128*1024)){
            while(true){
                int initialByte=in.read();if(initialByte<0)break;
                raw[0]=(byte)initialByte;
                int n=1;
                while(n<RECORD_BYTES){int k=in.read(raw,n,RECORD_BYTES-n);if(k<0)throw new EOFException("Última vela BIN incompleta");n+=k;}
                buffer.position(0);
                long t=Integer.toUnsignedLong(buffer.getInt());
                double o=buffer.getFloat(),h=buffer.getFloat(),l=buffer.getFloat(),c=buffer.getFloat();
                if(t<1230768000L||t>4102444800L||!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)||
                        l<=0||o<=0||c<=0||h<Math.max(o,c)-.01||l>Math.min(o,c)+.01||t%60!=0)
                    throw new IOException("Vela inválida en el registro "+(rows+1));
                if(prev!=-1&&t!=prev+60)
                    throw new IOException("BIN no continuo en el registro "+(rows+1)+": se esperaba una vela por minuto sin huecos");
                prev=t;if(first<0)first=t;last=t;rows++;
                month(t); // Include months with zero profits, too.
                long hour=t-t%HOUR;
                if(current==null)current=new Bar(hour,o,h,l,c);
                else if(hour!=current.t){
                    completeHour(current);
                    current=new Bar(hour,o,h,l,c);
                }else current.update(h,l,c);
                minute(t,h,l,c);
                if((rows&0x7fff)==0){
                    if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Test cancelado");
                    if(cb!=null)cb.progress(expectedRows>0?(int)Math.min(99,rows*100/expectedRows):-1,rows);
                }
            }
        }
        if(rows<ATR_PERIODS*60+SWING*60)
            throw new IOException("BIN demasiado corto para reconstruir ATR 50 y los pivotes de 5 horas");
        if(cancelled.get())throw new InterruptedException("Test cancelado");
        // The last potentially unfinished hourly candle is intentionally not confirmed.
        if(cb!=null)cb.progress(100,rows);
        return report();
    }
    private JSONObject report() throws Exception {
        JSONObject r=new JSONObject();
        double liquidValue=btc*lastClose*(1-FEE);
        double equity=cash+liquidValue;
        int count=0,profitable=0,streak=0,maxStreak=0;
        JSONArray months=new JSONArray();
        for(Map.Entry<Integer,Month> item:monthly.entrySet()){
            Month m=item.getValue();months.put(m.json());count++;
            if(m.profit>1e-8){profitable++;streak=0;}
            else{streak++;maxStreak=Math.max(maxStreak,streak);}
        }
        r.put("records",rows).put("start",first).put("end",last)
         .put("initial",initial).put("equity",equity).put("profitNet",equity-initial)
         .put("cash",cash).put("btc",btc).put("cost",cost)
         .put("average",btc>0?cost/btc:0)
         .put("unrealized",liquidValue-cost).put("realized",realized)
         .put("fees",fees).put("buyCount",buyCount).put("sellCount",sellCount)
         .put("positiveMonths",profitable).put("monthsCount",count)
         .put("maxDryMonths",maxStreak).put("maxDrawdown",maxDrawdown)
         .put("monthly",months).put("finalPrice",lastClose)
         .put("description","BTCUSD 1m→1h · FluidTrades 5/20/5 ATR50 · compra 10 % · venta 10 % de BTC restantes por zona Supply distinta · TP neto mínimo 1 % · comisión 0,10 % por lado · sin aportaciones");
        return r;
    }
    public static String monthlyCsv(JSONObject result){
        StringBuilder b=new StringBuilder("mes;beneficio_realizado_USD;compras;ventas\n");
        JSONArray arr=result.optJSONArray("monthly");
        if(arr==null)return b.toString();
        for(int i=0;i<arr.length();i++){
            JSONObject m=arr.optJSONObject(i);if(m==null)continue;
            b.append(m.optString("month")).append(';').append(String.format(Locale.US,"%.2f",m.optDouble("profit")))
             .append(';').append(m.optInt("buys")).append(';').append(m.optInt("sells")).append('\n');
        }
        return b.toString();
    }
}