package es.fluidgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Drawing only: the independent simulation engine remains the single source of truth.
 * Horizontal drag pans; pinch zooms candle width; double tap returns to live view.
 */
public final class ChartView extends View {
    private static final int BG=0xFF091521, GRID=0xFF27384A, TEXT=0xFFAFC2D0;
    private static final int GREEN=0xFF36C6A4, RED=0xFFF27483, WHITE=0xFFF1F7FB;
    private static final int ZONE_GREEN=0x3530CBA9, ZONE_RED=0x35F06B82;
    private static final long HOUR=3_600_000L;
    // Espacio libre a la derecha para distinguir las velas actuales del eje de precios.
    private static final float FUTURE_EMPTY_BARS=8f;
    private static final class Candle {
        final long t; final double o,h,l,c;
        Candle(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}
    }
    private static final class Zone {
        final long born; final double top,bottom;final boolean demand;
        Zone(long born,double top,double bottom,boolean demand){this.born=born;this.top=top;this.bottom=bottom;this.demand=demand;}
    }
    private static final class Trade {
        final long t;final double price;final boolean buy;
        Trade(long t,double price,boolean buy){this.t=t;this.price=price;this.buy=buy;}
    }
    private final List<Candle> candles=new ArrayList<>();
    private final List<Zone> zones=new ArrayList<>();
    private final List<Trade> trades=new ArrayList<>();
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private boolean showFarZones=false;
    private float zoom=1f,offsetBars=0f,downX,downY,lastX,stepPx;
    private boolean horizontalDrag=false, touchActive=false;
    private double livePrice=0;

    public ChartView(Context context){
        super(context);
        setClickable(true);
        fill.setStyle(Paint.Style.FILL);
        label.setColor(TEXT);label.setTextSize(dp(10));
        line.setStrokeWidth(dp(1));
        accent.setStrokeWidth(dp(1.2f));accent.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));
        marker.setTextAlign(Paint.Align.CENTER);marker.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);marker.setTextSize(dp(9));
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                zoomAt(d.getScaleFactor(),d.getFocusX());
                return true;
            }
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){resetView();return true;}
        });
    }
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    public void setShowFarZones(boolean value){showFarZones=value;invalidate();}
    public boolean isShowingFarZones(){return showFarZones;}
    public void resetView(){zoom=1f;offsetBars=0f;invalidate();}
    public void zoomIn(){zoomAt(1.35f,getWidth()*.60f);}
    public void zoomOut(){zoomAt(1f/1.35f,getWidth()*.60f);}
    private void zoomAt(float factor,float focusX){
        if(candles.isEmpty())return;
        int oldCount=visibleCount();
        float left=dp(8),plotWidth=Math.max(dp(80),getWidth()-dp(89));
        float fraction=Math.max(0,Math.min(1,(focusX-left)/plotWidth));
        float firstVisible=candles.size()-oldCount-offsetBars;
        float anchor=firstVisible+fraction*oldCount;
        zoom=Math.max(.40f,Math.min(8f,zoom*factor));
        int newCount=visibleCount();
        offsetBars=candles.size()-newCount-(anchor-fraction*newCount);
        clampOffset();
        invalidate();
    }
    public boolean isInteracting(){return touchActive;}
    public void setData(JSONArray bars,JSONArray zonesJson,JSONArray tradesJson,double price){
        long oldLast=candles.isEmpty()?0:candles.get(candles.size()-1).t;
        candles.clear();zones.clear();trades.clear();livePrice=price;
        if(bars!=null)for(int i=Math.max(0,bars.length()-720);i<bars.length();i++){
            JSONObject x=bars.optJSONObject(i);if(x==null)continue;
            double o=x.optDouble("o"),h=x.optDouble("h"),l=x.optDouble("l"),cl=x.optDouble("c");
            if(o>0 && h>=l) candles.add(new Candle(x.optLong("t"),o,h,l,cl));
        }
        if(zonesJson!=null)for(int i=0;i<zonesJson.length();i++){
            JSONObject x=zonesJson.optJSONObject(i);if(x==null)continue;
            double hi=x.optDouble("top"),lo=x.optDouble("bottom");
            if(hi>lo && lo>0)zones.add(new Zone(x.optLong("born"),hi,lo,"demand".equals(x.optString("type"))));
        }
        if(tradesJson!=null)for(int i=0;i<tradesJson.length();i++){
            JSONObject x=tradesJson.optJSONObject(i);if(x==null)continue;
            String type=x.optString("type");
            if(("COMPRA".equals(type)||"VENTA".equals(type))&&x.optDouble("price")>0)
                trades.add(new Trade(x.optLong("t"),x.optDouble("price"),"COMPRA".equals(type)));
        }
        if(offsetBars>0 && oldLast>0 && !candles.isEmpty()){
            long newLast=candles.get(candles.size()-1).t;
            if(newLast>oldLast)offsetBars+=(newLast-oldLast)/(float)HOUR;
        }
        clampOffset();invalidate();
    }
    private int visibleCount(){
        int n=(int)Math.round(48/zoom);
        return Math.min(candles.size(),Math.max(12,Math.min(120,n)));
    }
    private void clampOffset(){
        if(offsetBars<0)offsetBars=0;
        offsetBars=Math.min(offsetBars,Math.max(0,candles.size()-visibleCount()));
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(candles.isEmpty())return super.onTouchEvent(e);
        // The parent ScrollView must not steal the first swipe. One finger pans the chart.
        // Scroll the dashboard by dragging OUTSIDE the chart.
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            touchActive=true;
            lastX=e.getX();downX=lastX;downY=e.getY();horizontalDrag=false;
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        }
        gestures.onTouchEvent(e);
        scale.onTouchEvent(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(e.getPointerCount()>1){
                    int remaining=e.getActionIndex()==0?1:0;
                    lastX=e.getX(remaining);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>1||scale.isInProgress())return true;
                float dx=e.getX()-lastX;
                offsetBars+=dx/Math.max(dp(3),stepPx);
                clampOffset();
                invalidate();
                lastX=e.getX();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                touchActive=false;
                horizontalDrag=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();
                return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private String money(double v,int decimals){
        return String.format(new Locale("es","ES"),"%,."+decimals+"f",v);
    }
    private String time(long t){return new SimpleDateFormat("d MMM HH:mm",new Locale("es","ES")).format(new Date(t));}
    private float y(double price,double max,double span,float top,float height){
        return (float)(top+(max-price)/span*height);
    }
    private void triangle(Canvas c,float x,float y,boolean buy){
        float a=dp(6),h=dp(8);
        Path t=new Path();
        if(buy){t.moveTo(x,y-h/2);t.lineTo(x-a,y+h/2);t.lineTo(x+a,y+h/2);}
        else{t.moveTo(x,y+h/2);t.lineTo(x-a,y-h/2);t.lineTo(x+a,y-h/2);}
        t.close();fill.setColor(buy?GREEN:RED);c.drawPath(t,fill);
        marker.setColor(BG);c.drawText(buy?"C":"V",x,y+dp(3),marker);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        int w=getWidth(),h=getHeight();fill.setColor(BG);c.drawRect(0,0,w,h,fill);
        float left=dp(8),right=w-dp(81),top=dp(15),bottom=h-dp(26);
        if(right<left+dp(80))return;
        float width=right-left,height=bottom-top;
        if(candles.isEmpty()){
            label.setColor(TEXT);c.drawText("Esperando velas BTC 1h verificadas de OKX",dp(10),h/2f,label);return;
        }
        int n=visibleCount();
        float visibleFirst=Math.max(0f,candles.size()-n-offsetBars);
        int start=Math.max(0,(int)Math.floor(visibleFirst));
        int end=Math.min(candles.size(),(int)Math.ceil(visibleFirst+n));
        if(end<=start)return;
        long firstTime=candles.get(start).t,lastTime=candles.get(end-1).t;
        stepPx=width/Math.max(1f,n+FUTURE_EMPTY_BARS);
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(int i=start;i<end;i++){
            Candle v=candles.get(i);lo=Math.min(lo,v.l);hi=Math.max(hi,v.h);
        }
        double base=Math.max(hi-lo,Math.max(1,hi*.003));
        double nearLo=lo-base*.60,nearHi=hi+base*.60;
        List<Zone> drawn=new ArrayList<>();
        for(Zone z:zones){
            if(z.born>lastTime+HOUR)continue; // never draw a zone before it existed
            if(!showFarZones && (z.top<nearLo||z.bottom>nearHi))continue;
            drawn.add(z);
            if(showFarZones){lo=Math.min(lo,z.bottom);hi=Math.max(hi,z.top);}
        }
        double pad=Math.max((hi-lo)*.13,Math.max(1,hi*.001));
        lo-=pad;hi+=pad;
        if(livePrice>0 && end==candles.size() && livePrice<hi+pad && livePrice>lo-pad){
            lo=Math.min(lo,livePrice-pad*.15);hi=Math.max(hi,livePrice+pad*.15);
        }
        double span=Math.max(1e-8,hi-lo);
        label.setColor(TEXT);label.setTextSize(dp(10));line.setColor(GRID);
        for(int i=0;i<6;i++){
            float yy=top+height*i/5f;
            c.drawLine(left,yy,right,yy,line);
            c.drawText(money(hi-span*i/5.,0),right+dp(4),yy+dp(3),label);
        }
        // Draw zones behind candles. Supply/demand use the currently confirmed active list.
        c.save();c.clipRect(left,top,right,bottom);
        for(Zone z:drawn){
            float yt=y(z.top,hi,span,top,height),yb=y(z.bottom,hi,span,top,height);
            float xs=left;
            if(z.born>firstTime)xs=left+(float)(((z.born-candles.get(0).t)/(double)HOUR-visibleFirst)*stepPx);
            if(xs>=right)continue;
            fill.setColor(z.demand?ZONE_GREEN:ZONE_RED);
            c.drawRect(xs,yt,right,yb,fill);
            line.setColor(z.demand?0xB533C5AC:0xBDF27088);
            c.drawLine(xs,yt,right,yt,line);c.drawLine(xs,yb,right,yb,line);
            float ym=(yt+yb)/2;
            if(ym>top+dp(10)&&ym<bottom-dp(10)){
                label.setColor(z.demand?GREEN:RED);
                c.drawText(z.demand?"DEMAND":"SUPPLY",Math.max(left+dp(4),right-dp(64)),ym-dp(3),label);
                label.setColor(TEXT);
            }
        }
        // Candles
        float candleW=Math.max(dp(2),Math.min(dp(9),stepPx*.65f));
        for(int i=start;i<end;i++){
            Candle bar=candles.get(i);float x=left+stepPx*(i-visibleFirst+.5f);
            boolean up=bar.c>=bar.o;
            int color=up?GREEN:RED;
            line.setColor(color);line.setStrokeWidth(dp(1.1f));
            c.drawLine(x,y(bar.h,hi,span,top,height),x,y(bar.l,hi,span,top,height),line);
            float a=y(bar.o,hi,span,top,height),b=y(bar.c,hi,span,top,height);
            fill.setColor(color);
            c.drawRect(x-candleW/2,Math.min(a,b),x+candleW/2,Math.max(Math.max(a,b),Math.min(a,b)+dp(1.7f)),fill);
        }
        // Trade markers at their real simulated price and corresponding hourly candle.
        for(Trade tr:trades){
            if(tr.t<firstTime||tr.t>=lastTime+HOUR)continue;
            float index=(tr.t-candles.get(0).t)/(float)HOUR;
            float x=left+stepPx*(index-visibleFirst+.5f);
            if(x<left||x>right)continue;
            float yy=y(tr.price,hi,span,top,height)+(tr.buy?dp(10):-dp(10));
            if(yy<top+dp(7)||yy>bottom-dp(7))continue;
            triangle(c,x,yy,tr.buy);
        }
        // Live ticker: dashed price line clipped to chart; badge drawn outside clipping.
        float liveY=y(livePrice,hi,span,top,height);
        boolean showLive=offsetBars<1f && end==candles.size() && livePrice>0 && liveY>=top && liveY<=bottom;
        if(showLive){accent.setColor(GREEN);c.drawLine(left,liveY,right,liveY,accent);}
        c.restore();
        if(showLive){
            fill.setColor(0xFF10765F);
            c.drawRoundRect(new RectF(right,liveY-dp(13),w-dp(1),liveY+dp(13)),dp(4),dp(4),fill);
            label.setColor(WHITE);label.setTextSize(dp(10));
            c.drawText(money(livePrice,2),right+dp(4),liveY+dp(4),label);
        }
        label.setColor(TEXT);label.setTextSize(dp(10));
        c.drawText(time(firstTime),left,bottom+dp(16),label);
        String endLabel=time(lastTime);float endW=label.measureText(endLabel);
        // La última marca temporal acompaña a la última vela, no al borde del eje.
        float lastCandleX=left+stepPx*(end-visibleFirst-.5f);
        float endLabelX=Math.max(left+dp(60),Math.min(right-endW-dp(2),lastCandleX-endW*.5f));
        c.drawText(endLabel,endLabelX,bottom+dp(16),label);
    }
}