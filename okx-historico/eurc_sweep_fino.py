#!/usr/bin/env python3
"""Empirical EURC/USD(or historical EURC-USDC) miBot parameter sweep.
Only original OKX 1-minute spot monthly archives. No trades, API keys, forecasts.
Split 2024-10-31..2024-12-31 train, 2025-01-01..2025-03-19 holdout, 2026-09-21..30 live-like sample.
Commission .1% both sides, gross take-profit; 15k initial and monthly contributions.
"""
import os, sys, json, math, itertools
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
from numba import njit
from analyze_okx import get_month

DEST=Path('okx-historico');DEST.mkdir(exist_ok=True)
SYMBOL='EURC-USDC'; SYMS=[SYMBOL, 'BTC-USDC']

@njit(cache=True)
def simulate(ts, op, hi, lo, cl, dom, mi, final_today, n, cb, mc, mm, tp, buyfee=.001, sellfee=.001):
    # Model copies miBot TP convention and intrabar sequence.
    weights=np.empty(n,np.float64);levels=np.empty(n,np.float64)
    z=0.
    for i in range(n):
        weights[i]=mm**i;z+=weights[i]
    for i in range(n):weights[i]/=z
    levels[0]=1.;drop=0.
    for i in range(1,n):
        drop+=cb*(mc**(i-1));levels[i]=1-drop
    if levels[-1]<=0.:return np.zeros(16),np.zeros(50),np.zeros(50)

    capital=15000.;phase=0;month_last=-1;last_close=-1
    next_open=True;monthprof=np.zeros(50,np.float64);monthpay=np.zeros(50,np.float64)
    buy_prices=np.empty(n,np.float64);amounts=np.empty(n,np.float64)
    cash_manual=np.zeros(2,np.float64);mp=np.zeros(2,np.float64)
    active=-1;base=0.;external=0.; invested=0.;rawqty=0.;netqty=0.;next_buy=1
    oor=False;oor_start=0;oor_longest=0.;oor_count=0
    cycles=0;buys=0;paid=0.;profit=0.;deposit=0.;maxinv=0.;totalinv=0.;monthcontrib=np.zeros(50,np.float64)
    # Exposures sampled once per 1-day boundary; avoids overweighting long open periods via number of bars.
    for i in range(len(ts)):
        p=op[i];mx=hi[i];mn=lo[i]
        if next_open:
            base=capital;external=0.;invested=0.;rawqty=0.;netqty=0.;next_buy=1
            oor=False;oor_start=0;cash_manual[:]=0.;mp[:]=0.;active=-1
            for k in range(n):
                buy_prices[k]=p*levels[k];amounts[k]=base*weights[k]
            v=amounts[0];rawqty+=v/p;netqty+=v/p*(1.-buyfee);invested+=v;buys+=1
            next_open=False
        # Previously active manual buys, on open, before TP.
        for j in range(2):
            if cash_manual[j]>0. and i>=active and p<=mp[j]:
                old=(invested/rawqty)*(1.+tp)
                proposed=(invested+cash_manual[j])/(rawqty+cash_manual[j]/p)*(1.+tp)
                if proposed<old-1.e-9:
                    v=cash_manual[j];rawqty+=v/p;netqty+=v/p*(1.-buyfee);invested+=v;cash_manual[j]=0.;buys+=1
        target=(invested/rawqty)*(1.+tp)
        if mx>=target:
            gain=netqty*target*(1.-sellfee)-invested
            # this is always positive for TP > ~0.201% with 0.1% each way
            profit+=gain;cycles+=1;monthprof[mi[i]]+=gain
            if phase==1:
                pay=.8*max(0.,gain);paid+=pay;monthpay[mi[i]]+=pay;grow=gain-pay
            else:grow=gain
            capital=base+external+grow
            if phase==0 and capital>=25000.:phase=1
            if oor:oor_longest=max(oor_longest,(ts[i]-oor_start)/86400.)
            last_close=ts[i];next_open=True
        else:
            while next_buy<n and mn<=buy_prices[next_buy]:
                v=amounts[next_buy];px=buy_prices[next_buy]
                rawqty+=v/px;netqty+=v/px*(1.-buyfee);invested+=v;buys+=1;next_buy+=1
            for j in range(2):
                if cash_manual[j]>0. and i>=active and mn<=mp[j]:
                    old=(invested/rawqty)*(1.+tp)
                    proposed=(invested+cash_manual[j])/(rawqty+cash_manual[j]/mp[j])*(1.+tp)
                    if proposed<old-1.e-9:
                        v=cash_manual[j];px=mp[j]
                        rawqty+=v/px;netqty+=v/px*(1.-buyfee);invested+=v;cash_manual[j]=0.;buys+=1
            if next_buy==n and mn<buy_prices[n-1] and not oor:
                oor=True;oor_start=ts[i];oor_count+=1
        if final_today[i]:
            frac=invested/max(1.,base+external)
            maxinv=max(maxinv,frac);totalinv+=frac
            if dom[i]>=26 and mi[i]!=month_last and not next_open:
                month_last=mi[i]
                aporte=200. if phase==0 else (300. if oor else 0.)
                if aporte>0.:
                    deposit+=aporte;external+=aporte;monthcontrib[mi[i]]+=aporte
                    pending=cash_manual[0]+cash_manual[1]+aporte
                    cash_manual[0]=pending/2.;cash_manual[1]=pending/2.
                    avg=invested/netqty;mp[0]=avg*.99;mp[1]=avg*.98;active=i+1
    if next_open:equity=capital;unrealized=0.
    else:
        equity=base+external-invested+netqty*cl[-1]*(1.-sellfee)
        unrealized=equity-(base+external)
    if oor:oor_longest=max(oor_longest,(ts[-1]-oor_start)/86400.)
    nmon=int(mi[-1])+1
    monthsprof=0;monthspay=0;streak=0;maxstreak=0
    for i in range(nmon):
        if monthprof[i]>0.:monthsprof+=1;streak=0
        else:streak+=1
        maxstreak=max(maxstreak,streak)
        if monthpay[i]>0.:monthspay+=1
    out=np.zeros(16,np.float64)
    #0 net,1 realized,2 withdrawal,3 count,4 profmonths,5 paymonths,6 deposits,7 equity,
    #8 OOR entries,9 longest days OOR,10 max investment share,11 avg day exposure,12 max no-profit months,
    #13 months,14 open unrealized pnl, 15 buy count
    out[0]=equity+paid-15000.-deposit;out[1]=profit;out[2]=paid;out[3]=cycles
    out[4]=monthsprof;out[5]=monthspay;out[6]=deposit;out[7]=equity;out[8]=oor_count
    out[9]=oor_longest;out[10]=maxinv;out[11]=totalinv/max(1.,np.sum(final_today))
    out[12]=maxstreak;out[13]=nmon;out[14]=unrealized;out[15]=buys
    return out,monthprof,monthpay

def make_arrays(t,rows):
    date=t.astype('datetime64[s]').astype('datetime64[D]')
    month=date.astype('datetime64[M]')
    mi=(month-month[0]).astype('int32')
    dom=(date-month.astype('datetime64[D]')).astype('int32')+1
    fd=np.r_[date[1:]!=date[:-1],True]
    return (t.astype('int64'),*[rows[k].astype('float64') for k in 'ohlc'],dom,mi,fd)

def extract():
    blocks=[];sources=[]
    for y,m in [(2024,10),(2024,11),(2024,12),(2025,1),(2025,2),(2025,3),(2026,9)]:
        a,st=get_month(SYMBOL,y,m)
        if len(a): blocks.append(a)
        sources.append({'month':f'{y}-{m:02d}','bars':int(len(a)),'url':st.get('url'),'missing':bool(st.get('missing',False))})
        print('DOWNLOADED',sources[-1],flush=True)
    data=np.concatenate(blocks);data.sort(order='t');_,keep=np.unique(data['t'],return_index=True);data=data[np.sort(keep)]
    periods={'train':('2024-10-31T16:00:00','2024-12-31T23:59:00'),
             'validate':('2025-01-01T00:00:00','2025-03-19T09:02:00'),
             'recent':('2026-09-21T09:00:00','2026-09-30T15:59:00'),
             'all_old':('2024-10-31T16:00:00','2025-03-19T09:02:00')}
    arrays={};stats={}
    for label,(st,en) in periods.items():
        start=int(datetime.fromisoformat(st).replace(tzinfo=timezone.utc).timestamp());end=int(datetime.fromisoformat(en).replace(tzinfo=timezone.utc).timestamp())
        a=data[(data['t']>=start)&(data['t']<=end)]
        arrays[label]=make_arrays(a['t'],a)
        stats[label]={'start':st,'end':en,'bars':len(a),'coverage_pct':round(100*len(a)/(1+(end-start)//60),3),'price_min':float(a['l'].min()),'price_max':float(a['h'].max()),'initial_price':float(a['o'][0]),'terminal_price':float(a['c'][-1])}
        if stats[label]['coverage_pct']<99:raise RuntimeError(f'Bad coverage for {label}: {stats[label]}')
    return arrays,stats,sources

def rounded(result):
    names=('net_gain','realized_profit','withdrawal','closed_cycles','months_positive','months_with_withdrawals',
           'external_contributions','terminal_equity','oor_entries','longest_oor_days','max_capital_invested_fraction',
           'avg_daily_invested_fraction','longest_months_without_profit','months','open_unrealized_pnl','buy_orders')
    return {n:(round(float(v),4)) for n,v in zip(names,result)}

def main():
    arr,stats,sources=extract()
    # baseline verify existing full-sample miBot: TP gross1, N15 CB.5 MM1.2 MC1.2
    base=rounded(simulate(*arr['all_old'],15,.005,1.2,1.2,.01)[0]);print('BASELINE',base,flush=True)
    if abs(base['realized_profit']-49.15)>0.2 or abs(base['closed_cycles']-5)>0.1:
        raise RuntimeError('Cannot reproduce existing validated simulation baseline: '+str(base))
    # Fine sweep: keep N=15 and reduce CB, MC, MM and TP. No forced loss, commissions 0.1% each side.
    Ns=[15]
    CBs=[.0002,.0005,.001,.0015,.002,.0035,.005]
    MCs=[.75,.85,.95,1.,1.1,1.2]
    MMs=[.3,.45,.6,.75,.9,1.05,1.2]
    TPs=[.0025,.003,.004,.005,.0065,.0075,.01]
    configs=[]
    for n,cb,mc,mm,tp in itertools.product(Ns,CBs,MCs,MMs,TPs):
        coverage=sum(cb*mc**k for k in range(n-1))
        if coverage>.20:continue
        conf={'N':n,'CB_pct':round(cb*100,3),'MC':mc,'MM':mm,'TP_gross_pct':round(tp*100,3),'coverage_pct':round(100*coverage,3)}
        train=rounded(simulate(*arr['train'],n,cb,mc,mm,tp)[0]);valid=rounded(simulate(*arr['validate'],n,cb,mc,mm,tp)[0]);recent=rounded(simulate(*arr['recent'],n,cb,mc,mm,tp)[0])
        full=rounded(simulate(*arr['all_old'],n,cb,mc,mm,tp)[0])
        conf.update({'train':train,'validate':valid,'recent':recent,'all_old':full})
        configs.append(conf)
    print('COMBINATIONS',len(configs),flush=True)
    # Robust scoring: prioritize meaningful net profit, frequent profit, less immobilization, consistency of gains in both halves.
    # Rank not as promise, but as evidence-driven selection across regimes, separate holdout.
    def score(x):
        tr,va,fu,rc=[x[k] for k in ('train','validate','all_old','recent')]
        if tr['closed_cycles']<2 or va['closed_cycles']<2:return -1e9
        if min(tr['net_gain'],va['net_gain'])<=0:return -1e9
        if fu['max_capital_invested_fraction']>.90:return -1e9
        # Benefit monthly regularity, profit consistency, capital drawdown penalty.
        return (fu['net_gain'] + 50*fu['months_positive']
                +20*min(tr['months_positive'],va['months_positive'])
                - 50*fu['longest_months_without_profit']
                - 80*fu['oor_entries']
                - 100*fu['max_capital_invested_fraction']
                + 10*rc['closed_cycles'])
    ranked=sorted(configs,key=score,reverse=True)
    # Compare on baseline, top quality, top net, top cycles, and TP1 constrained.
    def top_sorted(key,k=8):return sorted(configs,key=lambda x:x['all_old'][key],reverse=True)[:k]
    acceptable=[x for x in ranked if score(x)>-1e8]
    tp1=[x for x in configs if x['TP_gross_pct']==1.0]
    bytp=[]
    for tp in TPs:
        z=[x for x in configs if x['TP_gross_pct']==round(tp*100,3)]
        bytp.append({'tp':round(100*tp,3),'top_net':max(z,key=lambda x:x['all_old']['net_gain']),
                     'top_robust':max(z,key=score)})
    result={'periods':stats,'sources':sources,'baseline':base,'count':len(configs),'best_score':ranked[:20],
            'best_net':top_sorted('net_gain',12),'best_cycles':top_sorted('closed_cycles',12),
            'tp1_best_robust':sorted(tp1,key=score,reverse=True)[:8],
            'tp1_best_net':sorted(tp1,key=lambda x:x['all_old']['net_gain'],reverse=True)[:8],
            'by_tp':bytp,'all_configs':configs,
            'notes':['No margin or selling at losses; gross TP as in miBot; both fees0.1%, OHLC fill optimism',
            'Train 2024Oct-Dec, validation 2025Jan-Mar, 2026Sep recent very short; no future guarantees',
            'Source archived market EURC-USDC, which no longer has continuous data in 2025-2026; latest market EURC/USD different orderbook',
            'No volume/slippage/order queue model; losses may persist when EURC devalues vs USDC']}
    out=DEST/'eurc_grid_sweep_fino_resultados.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    txt=DEST/'eurc_grid_sweep_fino_resumen.txt'
    with txt.open('w') as f:
        f.write('EURC-USDC optimización matemática, histórico mensual OKX 1 minuto, capital inicial 15.000 USDC.\n')
        f.write('Comisiones 0.10% comprar y 0.10% vender; TP bruto\n')
        f.write('Numero de combinaciones válidas: '+str(len(configs))+'\n')
        f.write('Base '+str(base)+'\n')
        for tag,records in [('Mejores equilibrio train+valid',ranked[:15]),('Mejores ganancia neta',top_sorted('net_gain',10)),('Mejores TP 1',sorted(tp1,key=score,reverse=True)[:10])]:
            f.write('\n'+tag+'\n')
            for x in records:f.write(str({k:x[k] for k in ('N','CB_pct','MC','MM','TP_gross_pct','coverage_pct')})+' TOTAL='+str(x['all_old'])+' TRAIN='+str(x['train']['net_gain'])+' VALID='+str(x['validate']['net_gain'])+' RECENT='+str(x['recent']['net_gain'])+'\n')
    print('FIXED N 15 smaller CB MC MM TP, fee gross threshold 0.2%',flush=True)
    print('TOP ROBUST',[(x['N'],x['CB_pct'],x['MM'],x['MC'],x['TP_gross_pct'],x['all_old']['net_gain'],x['all_old']['months_positive'],x['recent']['net_gain']) for x in ranked[:10]],flush=True)
    print('TOP TP1',[(x['N'],x['CB_pct'],x['MM'],x['MC'],x['all_old']['net_gain'],x['all_old']['months_positive']) for x in sorted(tp1,key=score,reverse=True)[:10]],flush=True)
    print('WRITTEN',out,txt,flush=True)

if __name__=='__main__':main()