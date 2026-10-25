"""Monotone warning-policy ablation using Hyundai ONLY. Never changes truth/windows."""
import json, math, subprocess, sys
from pathlib import Path

source=Path(sys.argv[1]); out=Path(sys.argv[2]); out.mkdir(parents=True,exist_ok=True)
rows=[json.loads(x) for x in source.read_text(encoding='utf8').splitlines()]
assert all(x['segment'].startswith('HYUNDAI_') for x in rows)
results=[]
for meters in (2.5,3.0,3.5,4.0):
    for degrees in (1.5,2.0,2.5,3.0):
        filtered=[]
        for row in rows:
            cards=[dict(c) for c in row['cards']]
            for c in cards:
                if c['kind']=='LANE_NO_SIGNAL' and (abs(c['lat_m'])<meters or c['peak_rad']<math.radians(degrees)):
                    c['visible']=False
            filtered.append(dict(segment=row['segment'],cards=cards))
        path=out/f'policy-{meters}-{degrees}.jsonl'
        path.write_text('\n'.join(json.dumps(x) for x in filtered)+'\n',encoding='utf8')
        score=subprocess.check_output([sys.executable,'-B',str(Path(__file__).with_name('score-hyundai.py')),str(path)],text=True,encoding='utf8')
        result=json.loads(score)
        results.append(dict(meters=meters,degrees=degrees,hit=result['clean_hit'],total=result['clean_total'],
            outside_per_hour=result['outside_per_hour'],missing_per_hour=result['missing_per_hour']))
print(json.dumps(results,indent=2))
