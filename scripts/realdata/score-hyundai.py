"""W16 tuning-only scoring. Original truth/tolerance/denominator are unchanged.
Kia is deliberately excluded from this entry point. No CSV is modified.
"""
import collections
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('original_eval', Path(__file__).with_name('ccs_eval_v2.py'))
E = importlib.util.module_from_spec(spec)
spec.loader.exec_module(E)
truth = [s for s in E.truth if s['model'].startswith('HYUNDAI_')]
engine = {j['segment']:j for j in map(json.loads, Path(sys.argv[1]).read_text(encoding='utf8').splitlines())}
assert all(s.startswith('HYUNDAI_') for s in engine), 'Tuning input must exclude holdout'
hits = collections.Counter(); totals = collections.Counter(); outside = collections.Counter()
hours = sum(s['moving_s'] for s in truth) / 3600
for segment in truth:
    cards = [c for c in engine.get(segment['segment'],{}).get('cards',[]) if c['visible'] and c['kind'] in E.LANE]
    used = set()
    for event in segment['truth']:
        overlap = [i for i,c in enumerate(cards) if c['start_ms'] < event['end_ms']+1500 and c['end_ms'] > event['start_ms']-1500]
        used.update(overlap)
        if not event['clean']: continue
        band = '<60' if event['kph'] < 60 else '60~100' if event['kph'] < 100 else '>=100'
        totals[band] += 1; hits[band] += bool(overlap)
    for i,c in enumerate(cards):
        if i not in used: outside[c['kind']] += 1
result = dict(split='HYUNDAI', segments=len(engine), clean_hit=sum(hits.values()), clean_total=sum(totals.values()),
    moving_hours=hours, outside=dict(outside), outside_per_hour=sum(outside.values())/hours,
    missing_per_hour=outside['LANE_NO_SIGNAL']/hours,
    bands={k:dict(hit=hits[k],total=v) for k,v in totals.items()})
print(json.dumps(result,ensure_ascii=False,indent=2))
