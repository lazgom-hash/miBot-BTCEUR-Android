#!/usr/bin/env python3
"""Auditable BTC-EUR vs BTC-USDC backtest, using original OKX monthly 1m archives.
No keys, no trades. Intrabar assumption: active OMs at open, TP, then buys at low.
Conversion USDC<->EUR: daily median of two OKX BTC close prices, not an FX reference.
"""
import os, io, csv, json, math, time, zipfile, urllib.request, urllib.parse, calendar
from datetime import datetime, timezone, timedelta
from pathlib import Path
import numpy as np

BASE=Path(os.getenv('OKX_DATA_DIR', 'okx-historico'))
BASE.mkdir(exist_ok=True,parents=True)
START=(2023,7); END=(2026,9)
SYM=('BTC-EUR','BTC-USDC')
DT=np.dtype([('t','<i8'),('o','<f8'),('h','<f8'),('l','<f8'),('c','<f8')])
API='https://www.okx.com/api/v5/public/market-data-history'

def month_iter():
    y,m=START
    while (y,m)<=END:
        yield (y,m)
        y,m=(y+1,1) if m==12 else (y,m+1)

def date_millis(y,m,day=1):return int(datetime(y,m,day,tzinfo=timezone.utc).timestamp()*1000)

def req(url,retries=4):
    err=None
    for i in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 miBot OKX historical comparison'}),timeout=75) as r:
                return r.read()
        except Exception as e:
            err=e;time.sleep(min(8,2**i))
    raise RuntimeError(f'{url}: {err}')

def archive_url(sym,y,m):
    yy,mm=(y+1,1) if m==12 else (y,m+1)
    q={'module':'2','instType':'SPOT','instIdList':sym,'dateAggrType':'monthly',
       'begin':str(date_millis(y,m)),'end':str(date_millis(yy,mm)-1)}
    x=json.loads(req(API+'?'+urllib.parse.urlencode(q)))
    if x.get('code')!='0':raise RuntimeError(f'Archivo {sym} {y}-{m}: {x}')
    choices=[g for d in x.get('data',[]) for k in d.get('details',[]) for g in k.get('groupDetails',[]) if g.get('url')]
    # monthly archives are named for the UTC+8 end boundary (often following month)
    if not choices:return None
    return choices[0]['url']

def decode_zip(payload,sym):
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        files=[name for name in z.namelist() if name.lower().endswith('.csv')]
        if not files:raise ValueError(f'No CSV in ZIP: {z.namelist()}')
        out=[]; skipped=0
        with z.open(files[0]) as bf:
            rows=csv.reader(io.TextIOWrapper(bf,encoding='utf-8-sig'))
            first=next(rows,None)
            if first is None:return np.empty(0,dtype=DT),{'header':'empty'}
            # OKX standard: timestamp,open,high,low,close,volume,...
            probe=first[0].strip().strip('"')
            if probe.isdigit(): src=iter([first]+list(rows));header='no header'
            else: src=rows;header=','.join(first)
            for row in src:
                try:
                    t=int(float(row[0]));t=t//1000 if t>1e11 else t
                    if t%60:skipped+=1;continue
                    op,hi,lo,cl=map(float,row[1:5])
                    if not(0<lo<=min(op,cl)<=max(op,cl)<=hi and np.isfinite(hi)):
                        skipped+=1;continue
                    out.append((t,op,hi,lo,cl))
                except Exception:skipped+=1
        a=np.array(out,dtype=DT)
        a.sort(order='t')
        return a,{'header':header,'rows':len(a),'invalid_rows':skipped,'zip_members':z.namelist()[:3]}

def get_month(sym,y,m):
    cache=BASE/'cache';cache.mkdir(exist_ok=True)
    p=cache/f'{sym}_{y}-{m:02d}.npy'
    if p.exists():
        a=np.load(p,allow_pickle=False)
        return a,{'cached':True,'rows':len(a)}
    url=archive_url(sym,y,m)
    if not url:return np.empty(0,dtype=DT),{'missing':True}
    payload=req(url)
    a,stat=decode_zip(payload,sym)
    if len(a):np.save(p,a,allow_pickle=False)
    stat.update({'zip_bytes':len(payload),'url':url})
    return a,stat

def load_data():
    report={};data={}
    for sym in SYM:
        parts=[];report[sym]={}
        for y,m in month_iter():
            key=f'{y}-{m:02d}'
            try:
                a,stat=get_month(sym,y,m)
                report[sym][key]=stat
                if len(a):parts.append(a)
                print('HIST',sym,key,'rows',len(a),'missing',stat.get('missing',False),flush=True)
            except Exception as e:
                report[sym][key]={'error':str(e)}
                print('ERROR',sym,key,str(e),flush=True)
        if not parts:raise RuntimeError(f'No historical candles for {sym}')
        x=np.concatenate(parts);x.sort(order='t')
        _,idx=np.unique(x['t'],return_index=True)
        data[sym]=x[np.sort(idx)]
    return data,report

def daily_fx(ts,usd,eur):
    day=ts//86400
    dd=np.unique(day)
    med=np.empty(len(dd),dtype='f8')
    ratio=usd['c']/eur['c']
    cuts=np.searchsorted(day,dd)
    for i,a in enumerate(cuts):
        b=cuts[i+1] if i+1<len(cuts) else len(ts)
        med[i]=np.median(ratio[a:b])
    assert np.all(np.isfinite(med)) and np.all(med>0)
    return dd,med

def compare_alignment(data):
    a,b=data[SYM[0]],data[SYM[1]]
    ts=np.intersect1d(a['t'],b['t'])
    a=a[np.searchsorted(a['t'],ts)]
    b=b[np.searchsorted(b['t'],ts)]
    dif=np.diff(ts)
    spans={z:{'first':int(data[z]['t'][0]),'last':int(data[z]['t'][-1]),'bars':len(data[z])} for z in SYM}
    stats={'symbols':spans,'common_bars':len(ts),'common_first':int(ts[0]),'common_last':int(ts[-1]),'shared_window_minutes':int((ts[-1]-ts[0])//60+1),'absent_minutes_shared_window':int((ts[-1]-ts[0])//60+1-len(ts)),
           'missing_rate_common_window_pct':round(100*((ts[-1]-ts[0])//60+1-len(ts))/((ts[-1]-ts[0])//60+1),3),
           'gaps_gt1m':int(np.sum(dif>60)),'max_gap_hours':round(float(dif.max())/3600,3)}
    return ts,a,b,stats

def run_bot(ts,rows,fx_daily,quote):
    # Same EUR-equivalent cash capital, same EUR contributions, and identical parameters.
    capital=15000.0*(fx_daily[0] if quote=='USDC' else 1.)
    phase='pre';n=15;mm=1.2;mc=1.2;cb=.005;tp=.01;buyfee=.001;sellfee=.001
    weights=np.array([mm**i for i in range(n)],dtype=float);weights/=weights.sum()
    levels=np.array([1-sum(cb*mc**k for k in range(i)) for i in range(n)])
    assert abs((1-levels[-1])*100-29.5979616)<.001
    starts=ts[0];last=ts[-1]
    days=ts//86400;    day_fx=fx_daily
    # per-bar fx is prepared outside; mutable quote conversion uses rate for current day
    ndays=int((days[-1]-days[0])+1)
    day_dt=[datetime.fromtimestamp(int((days[0]+j)*86400),timezone.utc) for j in range(ndays)]
    monthprof={};monthpersonal={};monthcycles={};monthdeposit={}
    cycles=0;buycount=0;deposit_eur=0.;personal_eur=0.;profit_real_eur=0.;longest_oor=0.;oor_count=0
    month_last_deposit=None;phase_change=None;last_close_cycle=None
    next_open=True
    invested=rawqty=netqty=external=0.
    cash_manual=[0.,0.];mp=[0.,0.];active=-1
    oor=False;oor_start=0;next_buy=1
    for i in range(len(ts)):
        ti=int(ts[i]);d=int(days[i]-days[0]);r=float(day_fx[d]);op=float(rows['o'][i]);hi=float(rows['h'][i]);lo=float(rows['l'][i]);cl=float(rows['c'][i])
        mm_key=day_dt[d].strftime('%Y-%m')
        if next_open:
            base=capital;external=0.;invested=rawqty=netqty=0.;next_buy=1
            oor=False;oor_start=0;cash_manual=[0.,0.];mp=[0.,0.];active=-1
            prices=op*levels;amounts=base*weights
            v=amounts[0];rawqty+=v/op;netqty+=v/op*(1-buyfee);invested+=v;buycount+=1
            next_open=False
        # A pending OM already marketable at the opening executes before TP.
        for j in range(2):
            if cash_manual[j]>0 and i>=active and op<=mp[j]:
                old_tp=(invested/rawqty)*(1+tp)
                proposed=(invested+cash_manual[j])/(rawqty+cash_manual[j]/op)*(1+tp)
                if proposed<old_tp-1e-9:
                    v=cash_manual[j];rawqty+=v/op;netqty+=v/op*(1-buyfee);invested+=v;cash_manual[j]=0.;buycount+=1
        target=invested/rawqty*(1+tp)
        if hi>=target:
            gain=netqty*target*(1-sellfee)-invested
            g_eur=gain/(r if quote=='USDC' else 1.)
            profit_real_eur+=g_eur;cycles+=1
            monthprof[mm_key]=monthprof.get(mm_key,0.)+g_eur
            monthcycles[mm_key]=monthcycles.get(mm_key,0)+1
            if phase=='post':
                pay=.8*max(0.,gain);growth=gain-pay
                personal_eur+=pay/(r if quote=='USDC' else 1.)
                monthpersonal[mm_key]=monthpersonal.get(mm_key,0.)+pay/(r if quote=='USDC' else 1.)
            else:growth=gain
            capital=base+external+growth
            if phase=='pre' and capital/(r if quote=='USDC' else 1.)>=25000.:
                phase='post';phase_change=ti
            if oor:longest_oor=max(longest_oor,(ti-oor_start)/86400.)
            last_close_cycle=ti;next_open=True
        else:
            while next_buy<n and lo<=prices[next_buy]:
                v=amounts[next_buy];p=prices[next_buy]
                rawqty+=v/p;netqty+=v/p*(1-buyfee);invested+=v;buycount+=1;next_buy+=1
            for j in range(2):
                if cash_manual[j]>0 and i>=active and lo<=mp[j]:
                    old_tp=(invested/rawqty)*(1+tp)
                    proposed=(invested+cash_manual[j])/(rawqty+cash_manual[j]/mp[j])*(1+tp)
                    if proposed<old_tp-1e-9:
                        v=cash_manual[j];p=mp[j];rawqty+=v/p;netqty+=v/p*(1-buyfee);invested+=v;buycount+=1;cash_manual[j]=0.
            if next_buy==n and lo<prices[-1] and not oor:oor=True;oor_start=ti;oor_count+=1
        # Apply contribution once at close of UTC day 26. In case of an outage, first later day in month.
        final_today=(i+1==len(ts)) or (days[i+1]>days[i])
        if final_today and day_dt[d].day>=26 and month_last_deposit!=mm_key and not next_open:
            month_last_deposit=mm_key
            aporte=(200. if phase=='pre' else (300. if oor else 0.))
            if aporte:
                v=aporte*(r if quote=='USDC' else 1.)
                deposit_eur+=aporte;external+=v;monthdeposit[mm_key]=monthdeposit.get(mm_key,0.)+aporte
                pending=sum(cash_manual)+v;cash_manual=[pending/2,pending/2]
                avg=invested/netqty;mp=[avg*.99,avg*.98];active=i+1
    r=float(day_fx[-1]);fxfactor=(r if quote=='USDC' else 1.)
    if next_open:equity=capital/fxfactor;unrealized=0.
    else:
        equity=(base+external-invested+netqty*float(rows['c'][-1])*(1-sellfee))/fxfactor
        unrealized=equity-(base+external)/fxfactor
    if oor:longest_oor=max(longest_oor,(int(ts[-1])-oor_start)/86400.)
    allmonths=[];dt=datetime.fromtimestamp(int(ts[0]),timezone.utc).replace(day=1)
    end=datetime.fromtimestamp(int(ts[-1]),timezone.utc)
    while dt.year<end.year or (dt.year==end.year and dt.month<=end.month):
        allmonths.append(dt.strftime('%Y-%m'));dt=dt.replace(year=dt.year+1,month=1) if dt.month==12 else dt.replace(month=dt.month+1)
    positive_months=sum(monthprof.get(m,0)>0 for m in allmonths)
    paid_months=sum(monthpersonal.get(m,0)>0 for m in allmonths)
    longest_streak=0;current=0
    for m in allmonths:
        current=0 if monthprof.get(m,0)>0 else current+1
        longest_streak=max(longest_streak,current)
    result={'market':quote,'start_utc':datetime.fromtimestamp(int(ts[0]),timezone.utc).isoformat(),
            'end_utc':datetime.fromtimestamp(int(ts[-1]),timezone.utc).isoformat(),
            'initial_eur':15000,'external_eur':round(deposit_eur,2),'personal_withdrawn_eur':round(personal_eur,2),
            'realized_bot_profit_eur':round(profit_real_eur,2),'equity_eur':round(equity,2),
            'net_wealth_gain_eur':round(equity+personal_eur-15000-deposit_eur,2),
            'unrealized_pnl_eur':round(unrealized,2),'cycles':cycles,'buy_orders':buycount,
            'months_profitable':positive_months,'months_withdrawals':paid_months,'months':len(allmonths),
            'longest_no_profit_streak_months':longest_streak,'oor_entry_count':oor_count,'longest_oor_days':round(longest_oor,2),
            'phase_change_utc':datetime.fromtimestamp(phase_change,timezone.utc).isoformat() if phase_change else None,
            'current_phase':phase,'last_cycle_close_utc':datetime.fromtimestamp(last_close_cycle,timezone.utc).isoformat() if last_close_cycle else None,
            'monthly':[{'month':m,'profit_eur':round(monthprof.get(m,0),2),'personal_eur':round(monthpersonal.get(m,0),2),'cycles':monthcycles.get(m,0),'contributions_eur':monthdeposit.get(m,0)} for m in allmonths]}
    return result

def main():
    data,source=load_data();ts,eur,usd,coverage=compare_alignment(data)
    if len(ts)<10000:raise RuntimeError('Fewer than 10000 comparable one-minute bars')
    dd,fx=daily_fx(ts,usd,eur)
    # Each minute maps to its day's implied FX; calendar days missing in common samples are carried forward.
    day=ts//86400;all_days=np.arange(day[0],day[-1]+1)
    fx_by_day=np.interp(all_days,dd,fx)
    results={'metadata':{'source':'OKX original 1-minute monthly archives, SPOT','period_requested':'2023-07 to 2026-09','funding':'spot no leverage','fee_buy':.001,'fee_sell':.001,'tp_gross':.01,'N':15,'CB':.005,'MM':1.2,'MC':1.2,'phase_threshold_eur':25000,'pre_eur_per_month':200,'post_oor_eur_per_month':300,'post_split_personal':.8,
      'fx_conversion':'Daily median of simultaneous OKX BTC-USDC / BTC-EUR close ratios; USDC treated as USD; not a real EUR/USDC conversion execution',
      'limitations':'No order books at fill times; OHLC intraminute order unknown; limits assumed filled if touched; no slippage, queue, tick-size, partial fill, FX conversion fee; unmatched minutes omitted.'},
      'coverage':coverage,'archive_audit':source}
    for name,rows,quote in [('BTC-EUR',eur,'EUR'),('BTC-USDC',usd,'USDC')]:
        print('SIMULATING',name,flush=True)
        results[name]=run_bot(ts,rows,fx_by_day,quote)
        print('DONE',name,{k:v for k,v in results[name].items() if k!='monthly'},flush=True)
    a,b=results['BTC-EUR'],results['BTC-USDC']
    results['difference_eur_minus_usdc']={k:round(a[k]-b[k],2) for k in ['net_wealth_gain_eur','equity_eur','realized_bot_profit_eur','personal_withdrawn_eur','external_eur','unrealized_pnl_eur','longest_oor_days']}
    results['difference_eur_minus_usdc'].update({k:a[k]-b[k] for k in ['cycles','months_profitable','months_withdrawals']})
    out=BASE/'resultado_okx_real.json';out.write_text(json.dumps(results,indent=2,ensure_ascii=False))
    with (BASE/'resumen_okx.txt').open('w') as f:
        print('miBot 3.5 · BTC/EUR frente BTC/USDC · HISTORICO OFICIAL OKX',file=f)
        print('Period:',results['BTC-EUR']['start_utc'],'->',results['BTC-EUR']['end_utc'],file=f)
        print('Timeframe: 1m, common timestamps',coverage['common_bars'],'missing',coverage['missing_rate_common_window_pct'],'%',file=f)
        print('RISK: prices from candles do not prove real fills; USD/EUR via implied ratio, no live FX conversion.',file=f)
        for k in ['BTC-EUR','BTC-USDC']:
            v=results[k];print(k,{z:v[z] for z in ['net_wealth_gain_eur','personal_withdrawn_eur','realized_bot_profit_eur','equity_eur','unrealized_pnl_eur','external_eur','cycles','months_profitable','months_withdrawals','longest_oor_days','phase_change_utc']},file=f)
        print('DIFF EUR minus USDC',results['difference_eur_minus_usdc'],file=f)
    print('RESULTS WRITTEN TO',out)

if __name__=='__main__':main()