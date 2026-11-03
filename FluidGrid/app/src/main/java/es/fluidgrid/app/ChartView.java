package es.fluidgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Simple 1h candlestick chart with active FluidTrades zones. */
public final class ChartView extends View {
    private static final int BG = 0xFF0C1A28;
    private static final int GRID = 0xFF274158;
    private static final int TXT = 0xFFA4BBD0;
    private static final int GREEN = 0xFF59D9A3;
    private static final int RED = 0xFFFF888D;
    private static final int DEMAND = 0x5536D0BF;
    private static final int SUPPLY = 0x55D95B75;

    private static final class Candle {
        long t; double o,h,l,c;
        Candle(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}
    }
    private static final class Zone {
        long born; double top,bottom; String type;
        Zone(long born,double top,double bottom,String type){this.born=born;this.top=top;this.bottom=bottom;this.type=type;}
    }

    private final Paint bg = new Paint();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint demand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint supply = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Candle> candles = new ArrayList<>();
    private final List<Zone> zones = new ArrayList<>();

    public ChartView(Context c){
        super(c);
        bg.setColor(BG);
        grid.setColor(GRID); grid.setStrokeWidth(dp(1));
        text.setColor(TXT); text.setTextSize(dp(10));
        wick.setStrokeWidth(dp(1.25f));
        demand.setColor(DEMAND);
        supply.setColor(SUPPLY);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(dp(1));
    }

    private float dp(float n){ return n*getResources().getDisplayMetrics().density; }

    public void setData(JSONArray bars, JSONArray activeZones){
        candles.clear(); zones.clear();
        if(bars!=null){
            int start = Math.max(0, bars.length()-72);
            for(int i=start;i<bars.length();i++){
                JSONObject b = bars.optJSONObject(i); if(b==null) continue;
                candles.add(new Candle(b.optLong("t"), b.optDouble("o"), b.optDouble("h"), b.optDouble("l"), b.optDouble("c")));
            }
        }
        if(activeZones!=null){
            for(int i=0;i<activeZones.length();i++){
                JSONObject z = activeZones.optJSONObject(i); if(z==null) continue;
                zones.add(new Zone(z.optLong("born"), z.optDouble("top"), z.optDouble("bottom"), z.optString("type")));
            }
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(), h=getHeight();
        c.drawRect(0,0,w,h,bg);
        if(candles.isEmpty()){
            c.drawText("Sin histórico horario suficiente", dp(14), h/2f, text);
            return;
        }
        float left = dp(10), right = w-dp(46), top = dp(8), bottom = h-dp(18);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for(Candle x: candles){ min = Math.min(min, x.l); max = Math.max(max, x.h); }
        for(Zone z: zones){ min = Math.min(min, z.bottom); max = Math.max(max, z.top); }
        if(!(max>min)) { max=min+1; }
        double pad = (max-min)*0.05; min -= pad; max += pad;
        float chartH = bottom-top, chartW = right-left;
        // grid
        for(int i=0;i<5;i++){
            float y = top + chartH*i/4f;
            c.drawLine(left,y,right,y,grid);
            double price = max - (max-min)*i/4d;
            c.drawText(format(price), right+dp(4), y+dp(4), text);
        }
        long firstT = candles.get(0).t, lastT = candles.get(candles.size()-1).t;
        long span = Math.max(1L, lastT-firstT);
        // zones
        for(Zone z: zones){
            float yTop = (float)(top + (max-z.top)/(max-min)*chartH);
            float yBot = (float)(top + (max-z.bottom)/(max-min)*chartH);
            float xStart = left + Math.max(0f, Math.min(chartW, (float)((z.born-firstT)/(double)span)*chartW));
            RectF r = new RectF(xStart, yTop, right, yBot);
            boolean demandZone = "demand".equals(z.type);
            c.drawRect(r, demandZone?demand:supply);
            border.setColor(demandZone?0xAA59D9A3:0xAAFF888D);
            c.drawRect(r, border);
        }
        // candles
        float step = chartW / candles.size();
        float bodyW = Math.max(dp(2), step*0.62f);
        for(int i=0;i<candles.size();i++){
            Candle x = candles.get(i);
            float cx = left + step*(i+0.5f);
            float yH = (float)(top + (max-x.h)/(max-min)*chartH);
            float yL = (float)(top + (max-x.l)/(max-min)*chartH);
            float yO = (float)(top + (max-x.o)/(max-min)*chartH);
            float yC = (float)(top + (max-x.c)/(max-min)*chartH);
            boolean up = x.c>=x.o;
            int col = up?GREEN:RED;
            wick.setColor(col); body.setColor(col);
            c.drawLine(cx, yH, cx, yL, wick);
            float rectTop = Math.min(yO,yC), rectBot = Math.max(yO,yC);
            if(rectBot-rectTop < dp(1.5f)) rectBot = rectTop + dp(1.5f);
            c.drawRect(cx-bodyW/2f, rectTop, cx+bodyW/2f, rectBot, body);
        }
        // bottom labels
        c.drawText("-72h", left, h-dp(4), text);
        c.drawText("Ahora", right-dp(20), h-dp(4), text);
    }

    private String format(double v){
        long rounded = Math.round(v);
        String s = String.valueOf(rounded);
        StringBuilder out = new StringBuilder();
        for(int i=0;i<s.length();i++){
            if(i>0 && (s.length()-i)%3==0) out.append('.');
            out.append(s.charAt(i));
        }
        return out.toString();
    }
}