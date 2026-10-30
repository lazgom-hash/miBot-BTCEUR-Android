#!/usr/bin/env python3
"""EURC/USDC vs BTC/USDC, historical official OKX 1m spot.
Same N15, CB .5%, MM 1.2, MC 1.2, TP gross 1%, buy/sell fees .1%.
All results denominated in USDC; no EUR conversion.
"""
import os,json,numpy as np
from datetime import datetime,timezone
from pathlib import Path
from analyze_okx import get_month,run_bot,DT
os.environ.setdefault('OKX_DATA_DIR','okx-historico')
DEST=Path('okx-historico');DEST.mkdir(exist_ok=True)
START=int(datetime(2024,10,21,8,0,tzinfo=timezone.utc).timestamp())
END=int(datetime(2026,9,30,15,59,tzinfo=timezone.utc).timestamp())
def months():
 for y in (2024,2025,2026):
  for m in range(1,13):
   if (y,m)>=(2024,10) and (y,m)<=(2026,9):yield y,m
data={};audit={}
for sym in ('EURC-USDC','BTC-USDC'):
 blocks=[];audit[sym]={}
 for y,m in months():
  try:
   a,st=get_month(sym,y,m)
   if len(a):blocks.append(a)
   audit[sym][f'{y}-{m:02d}']={'rows':len(a),'missing':bool(st.get('missing',False)),'url':st.get('url')}
   print('ARCHIVE',sym,y,m,len(a),flush=True)
  except Exception as exc:
   audit[sym][f'{y}-{m:02d}']={'error':str(exc)}
   print('ARCHIVE_ERROR',sym,y,m,str(exc),flush=True)
 if not blocks:raise RuntimeError('No historical OKX candles for '+sym)
 a=np.concatenate(blocks);a.sort(order='t');a=a[(a['t']>=START)&(a['t']<=END)]
 _,ii=np.unique(a['t'],return_index=True);data[sym]=a[np.sort(ii)]
 print('COMBINED',sym,len(data[sym]),flush=True)
ts=np.intersect1d(data['EURC-USDC']['t'],data['BTC-USDC']['t'])
if len(ts)<100000:raise RuntimeError('Too little common history '+str(len(ts)))
scenarios={}
for sym in ('EURC-USDC','BTC-USDC'):
 a=data[sym];a=a[np.searchsorted(a['t'],ts)]
 all_days=np.arange(ts[0]//86400,ts[-1]//86400+1)
 fx=np.ones(len(all_days))
 scenarios[sym]=run_bot(ts,a,fx,'EUR')
 # run_bot quote='EUR' intentionally treats all numbers as quote USDC and does no FX conversion
 scenarios[sym]['market']=sym
 r=a['c'];returns=np.diff(np.log(r));dif=np.diff(ts)
 scenarios[sym]['observed']={'first_price':float(r[0]),'last_price':float(r[-1]),
   'low_observed':float(np.min(a['l'])),'high_observed':float(np.max(a['h'])),
   'minute_abs_return_median_pct':float(np.median(np.abs(returns))*100),
   'minute_abs_return_p99_pct':float(np.percentile(np.abs(returns),99)*100),
   'daily_close_vol_pct_annualized':float(np.std(np.diff(np.log(r[np.r_[np.where(np.diff(ts//86400)!=0)[0],len(r)-1]])))*np.sqrt(365)*100),
   'abnormal_5pct_candles':int(np.count_nonzero((a['h']/a['o']>1.05)|(a['l']/a['o']<0.95))),
   'missing_large_gaps':int(np.sum(dif>60))}
 print('RESULT',sym,{k:v for k,v in scenarios[sym].items() if k not in ('monthly','observed')},flush=True)
a=scenarios['EURC-USDC'];b=scenarios['BTC-USDC']
result={'source':'OKX monthly candlestick original ZIP, 1 minute, both pairs same timestamps',
'currency':'All monetary amounts nominal USDC, no USD/EUR conversion',
'method':'Same copied miBot model, N15 CB0.5% MM1.2 MC1.2 TP gross1%; buy and sell fee0.1%; deposits 200 pre25K and 300 post OOR; post25K profit split 80/20',
'metadata':{'start':datetime.fromtimestamp(int(ts[0]),timezone.utc).isoformat(),'end':datetime.fromtimestamp(int(ts[-1]),timezone.utc).isoformat(),'common_candles':len(ts),'window_minutes':int((ts[-1]-ts[0])//60+1),'missing_pct':round((1-len(ts)/((ts[-1]-ts[0])//60+1))*100,4)},
'audit':audit,'scenarios':scenarios,'difference_eurc_minus_btc':{k:round(a[k]-b[k],2) for k in ('net_wealth_gain_eur','personal_withdrawn_eur','realized_bot_profit_eur','external_eur','equity_eur','cycles','months_withdrawals','months_profitable')},
'limitations':['OHLC fills assumed when touched, no maker queue/slippage/order book','Same 15K initial funding in USDC and no conversion to euros','EURC stablecoin peg and regulatory/redemption/liquidity risk not modeled','BTC bot and EURC bot have different economic risk even in same quote currency'] }
(DEST/'resultado_eurc_usdc_vs_btc_usdc.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
with (DEST/'informe_eurc_usdc_vs_btc_usdc.txt').open('w') as f:
 f.write('EURC/USDC frente BTC/USDC con histórico mensual original OKX y miBot N15.\n'+json.dumps({k:v for k,v in result.items() if k not in ('audit','scenarios')},ensure_ascii=False,indent=2)+'\n')
 for sym in scenarios:
  v=scenarios[sym]
  f.write('\n'+sym+'\n'+json.dumps({k:z for k,z in v.items() if k!='monthly'},ensure_ascii=False,indent=2)+'\n')
print('FINISHED',flush=True)
