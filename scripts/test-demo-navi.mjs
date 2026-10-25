import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {spawnSync,spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
const read=p=>readFileSync(new URL(p,import.meta.url),'utf8').replace(/^\uFEFF/,'');
const f=JSON.parse(read('./recap-scenario-f-navi-demo.json'));
const dir=new URL('../.local/w10-feeder-tests/',import.meta.url);mkdirSync(dir,{recursive:true});
const script=fileURLToPath(new URL('./demo-navi-feed.ps1',import.meta.url));
function dry(data){
  const path=new URL('dry.json',dir);writeFileSync(path,JSON.stringify(data));
  return spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',script,'-ScenarioFile',fileURLToPath(path),'-AdbPath','not-an-adb','-DryRun'],{encoding:'utf8'});
}
test('F is a single-clock plan and cannot accidentally run without navigation transport',()=>{
  const ctx={location:{hostname:'127.0.0.1',port:'8088'},window:{},fetch:()=>assert.fail('no IO'),URL,URLSearchParams};
  vm.runInNewContext(read('./recap-replay.js'),ctx);
  assert.throws(()=>ctx.window.driveRecapReplay.preflight(f),/demo-navi-feed/);
  assert.equal(f.steps[0].controls.gear,'P');assert.equal(f.steps.at(-1).controls.gear,'P');
  assert.equal(f.expected.length,4);
  assert.deepEqual(f.steps.filter(s=>s.navi).map(s=>[s.navi.action,s.navi.distanceMeters]),[[null,null],['TURN_RIGHT',200],['TURN_RIGHT',50],['TURN_UTURN',200],['TURN_UTURN',50],['TURN_STRAIGHT',200],[null,null]]);
  assert.ok(f.steps.every(s=>Number.isInteger(s.controls.speedKph)&&s.controls.brake===0));
  assert.equal(f.steps.find(s=>s.controls.steering===20).atMs-f.steps.find(s=>s.controls.turnSignal==='Blink right').atMs,2000);
});
test('PowerShell preflight is offline and rejects private fields, fractional speed and invalid maneuvers',()=>{
  const good=dry(f);assert.equal(good.status,0,good.stderr);assert.match(good.stdout,/No HTTP or adb/);
  for(const mutate of [x=>x.steps[0].controls.speedKph=1.2,x=>x.steps[0].navi.latitude=0,
    x=>x.steps[2].navi.action='RIGHT',x=>x.steps[2].navi.distanceMeters=-1,
    x=>x.steps[1].atMs=0,x=>x.steps[0].controls.vin='forbidden',x=>x.steps[0].controls.gear='D']){
    const bad=structuredClone(f);mutate(bad);assert.notEqual(dry(bad).status,0);
  }
});
test('receiver is manifest-exported only in Debug and does not auto-start a service or toggle',()=>{
  const debug=read('../app/src/debug/AndroidManifest.xml'),main=read('../app/src/main/AndroidManifest.xml');
  assert.match(debug,/DemoNaviReceiver/);assert.match(debug,/:drive_recorder/);assert.ok(!main.includes('DemoNaviReceiver'));
  const receiver=read('../app/src/debug/java/lab/pleos/observe/DemoNaviReceiver.kt');
  assert.match(receiver,/BuildConfig.DEBUG/);assert.ok(!/startService|startForegroundService|setDemoNaviEnabled/.test(receiver));
  const repo=read('../app/src/main/java/lab/pleos/observe/RecapRepository.kt');
  assert.match(repo,/requestedDemoNavi = false/);assert.ok(!repo.includes('putBoolean("demoNavi'));
});
test('mock Console and fake adb exercise same-step ordering, heartbeat and final Park without an emulator',async t=>{
  const events=[],state={speed:0,gear:'Park'};
  const server=createServer((req,res)=>{
    const u=new URL(req.url,'http://127.0.0.1');res.setHeader('content-type','application/json');
    if(req.method==='GET'&&u.pathname==='/vehicle/catalog')res.end(JSON.stringify({features:[{id:'turn-signal-state',write_endpoint:'/vehicle/turn-signal',control:{field:'mode',options:['Off','BlinkLeft','BlinkRight'].map(value=>({value}))}}]}));
    else if(req.method==='GET'&&u.pathname==='/vehicle/state')res.end(JSON.stringify({controls:{speed_kph:state.speed},gear:{actual:{value:state.gear}}}));
    else if(req.method==='POST'&&['/vehicle/controls','/vehicle/gear','/vehicle/turn-signal'].includes(u.pathname)){
      events.push({path:u.pathname,query:Object.fromEntries(u.searchParams),at:Date.now()});
      if(u.pathname==='/vehicle/controls')state.speed=Number(u.searchParams.get('speed'));
      if(u.pathname==='/vehicle/gear')state.gear=u.searchParams.get('gear');res.end('{}');
    }else{res.statusCode=404;res.end('{}');}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const packets=new URL('packets.jsonl',dir),fakeModule=new URL('fake-adb.mjs',dir),fakeCmd=new URL('fake-adb.cmd',dir),scenario=new URL('mini.json',dir);
  writeFileSync(packets,'');
  writeFileSync(fakeModule,`import{appendFileSync}from'node:fs';const a=process.argv.slice(2);const p=JSON.parse(Buffer.from(a[a.indexOf('payloadB64')+1],'base64').toString('utf8'));appendFileSync(new URL('./packets.jsonl',import.meta.url),JSON.stringify({p,at:Date.now()})+'\\n');console.log('Broadcast completed: result=0, data="demo_navi_accepted"');`);
  writeFileSync(fakeCmd,`@echo off\r\n"${process.execPath}" "${fileURLToPath(fakeModule)}" %*\r\n`);
  writeFileSync(scenario,JSON.stringify({runner:'demo-navi-feed.ps1',steps:[
    {atMs:0,controls:{gear:'P',speedKph:0,steering:0,brake:0},navi:{guiding:false,action:null,distanceMeters:null},caption:'fixture start'},
    {atMs:100,controls:{gear:'D',speedKph:12,steering:0,brake:0},navi:{guiding:true,action:'TURN_RIGHT',distanceMeters:50},caption:'fixture guide'},
    {atMs:1700,controls:{speedKph:12,steering:80,brake:0},caption:'fixture angle'},
    {atMs:3400,controls:{gear:'P',speedKph:0,steering:0,brake:0},navi:{guiding:false,action:null,distanceMeters:null},caption:'fixture end'}]}));
  const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',script,'-ScenarioFile',fileURLToPath(scenario),'-AdbPath',fileURLToPath(fakeCmd),'-ConsoleBaseUrl',`http://127.0.0.1:${server.address().port}`,'-StartImmediately'],{windowsHide:true});
  let stdout='',stderr='';child.stdout.on('data',d=>stdout+=d);child.stderr.on('data',d=>stderr+=d);
  const timeout=setTimeout(()=>child.kill(),20000);t.after(()=>{clearTimeout(timeout);if(child.exitCode===null)child.kill();});
  const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',resolve);});clearTimeout(timeout);
  assert.equal(code,0,stderr+'\n'+stdout);
  const received=readFileSync(packets,'utf8').trim().split('\n').map(s=>JSON.parse(s));
  assert.ok(received.length>=5);assert.equal(received[0].p.guiding,false);
  assert.ok(received.every((x,i)=>x.p.sequence===i));assert.equal(new Set(received.map(x=>x.p.runId)).size,1);
  assert.ok(received.every(x=>Object.keys(x.p).sort().join()===['guiding','action','distanceMeters','runId','sequence'].sort().join()));
  const drive=events.find(e=>e.query.gear==='Drive'),guide=received.find(x=>x.p.guiding);
  assert.ok(guide.at<=drive.at);assert.equal(state.gear,'Park');assert.equal(state.speed,0);
  assert.ok(events.filter(e=>e.path==='/vehicle/controls').every(e=>['speed','steering','brake'].every(k=>Object.hasOwn(e.query,k))));
});
