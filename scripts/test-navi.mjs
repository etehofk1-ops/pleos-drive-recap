import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import {KINDS,summarizeInput} from '../ai-proxy/contract.mjs';
const read = p => readFileSync(new URL(p,import.meta.url),'utf8').replace(/^\uFEFF/,'');
const scenario = JSON.parse(read('./recap-scenario-e-navi.json'));
test('turn kinds and v2 counts are accepted without exporting navigation snapshots',()=>{
  for(const kind of ['TURN_NO_SIGNAL','TURN_LATE_SIGNAL','TURN_SIGNALLED']) assert.ok(KINDS.includes(kind));
  const input={today:[{kind:'TURN_NO_SIGNAL',hourBucket:18,speedBand:'low',indicator:'none'}],recentDays:[{date:'D-1',counts:{missing:0,late:0,good:0,hardBrake:0,turnMissing:1,turnLate:0,turnGood:1}}]};
  assert.deepEqual(summarizeInput(input),input);
  for(const key of ['latitude','longitude','distanceMeters','turnType','route','description'])
    assert.throws(()=>summarizeInput({...input,today:[{...input.today[0],[key]:1}]}));
  assert.throws(()=>summarizeInput({...input,recentDays:[{date:'D-1',counts:{missing:0,late:0,good:0,hardBrake:0,turnMissing:1}}]}));
});
test('scenario E phases use the existing real HTTP replay, integral speed and separate parking',()=>{
  const context={location:{hostname:'127.0.0.1',port:'8088'},window:{},fetch:()=>assert.fail('offline preflight must not fetch'),URL,URLSearchParams};
  vm.runInNewContext(read('./recap-replay.js'),context);
  assert.deepEqual(Object.keys(scenario.phases),['right-turn','uturn','lane']);
  for(const phase of Object.values(scenario.phases)){
    assert.equal(phase.steps[0].controls.gear,'P'); assert.equal(phase.steps.at(-1).controls.gear,'P');
    assert.equal(context.window.driveRecapReplay.preflight(phase).steps.length,phase.steps.length);
    assert.ok(phase.steps.every(s=>Number.isInteger(s.controls.speedKph)));
    assert.ok(phase.steps.every(s=>s.controls.brake===0));
  }
  assert.ok(scenario.phases['right-turn'].steps.some(s=>s.controls.turnSignal==='Blink right'));
  assert.ok(scenario.phases.uturn.steps.every(s=>!s.controls.turnSignal || s.controls.turnSignal==='Off'));
  assert.equal(scenario.phases.lane.requiredNavigation.guiding,false);
  assert.ok(!JSON.stringify(scenario).includes('latitude'));
});
test('geo preflight rejects null template, accepts invariant numeric public fixture and never calls adb',()=>{
  const dir=new URL('../.local/w9-geo-tests/',import.meta.url);mkdirSync(dir,{recursive:true});
  const path=new URL('route.json',dir);
  const script=fileURLToPath(new URL('./recap-navi-location.ps1',import.meta.url));
  const run=(data,serial='emulator-5554')=>{
    writeFileSync(path,JSON.stringify(data));
    return spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',script,'-Phase','right-turn','-RouteFile',fileURLToPath(path),'-Serial',serial,'-AdbPath','not-a-real-adb','-DryRun'],{encoding:'utf8'});
  };
  assert.notEqual(run(JSON.parse(read('./recap-navi-route-template.json'))).status,0);
  const valid={'right-turn':[{atMs:0,longitude:0.1,latitude:0.2},{atMs:1000,longitude:0.101,latitude:0.201}]};
  const result=run(valid);assert.equal(result.status,0,result.stderr);assert.match(result.stdout,/No adb command/);assert.ok(!result.stdout.includes('0.101'));
  assert.notEqual(run(valid,'physical-device').status,0);
  for(const longitude of [null,181,'127.1',true])assert.notEqual(run({'right-turn':[{atMs:0,longitude,latitude:0},{atMs:1000,longitude:1,latitude:1}]}).status,0);
  assert.notEqual(run({'right-turn':[{atMs:0,longitude:0,latitude:0},{atMs:0,longitude:1,latitude:1}]}).status,0);
});
