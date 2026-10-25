import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const read=p=>readFileSync(new URL(p,import.meta.url),'utf8').replace(/^\uFEFF/,'');
const capture=fileURLToPath(new URL('./capture-responsive.ps1',import.meta.url));
const run=(...args)=>spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',capture,'-AdbPath','not-an-adb',...args],{encoding:'utf8'});

test('all capture actions default to an offline plan, including resize/theme/restore',()=>{
  for(const action of ['Inspect','Capture','Day','Night','System','Restore','Resize']) {
    const r=run('-Action',action,...(action==='Resize'?['-Size','840x1212']:[]));
    assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/PLAN ONLY/);
  }
});
test('capture rejects malformed sizes, path labels and non-emulator targets before invoking ADB',()=>{
  for(const args of [ ['-Action','Resize','-Size','840.5x1212'], ['-Action','Resize','-Size','1x1'],
    ['-Action','Resize','-Size','9999x1212'], ['-Label','../outside'], ['-Serial','physical-device'] ]) {
    assert.notEqual(run(...args).status,0,JSON.stringify(args));
  }
});
test('all Activities use system recreation without orientation/aspect/compat locks',()=>{
  const manifest=read('../app/src/main/AndroidManifest.xml');
  assert.doesNotMatch(manifest,/android:(screenOrientation|configChanges|maxAspectRatio|minAspectRatio|requiresSmallestWidthDp)=/);
  for(const name of ['MainActivity','LegacyLabActivity','NaviDemoActivity']) {
    assert.match(manifest,new RegExp('<activity[^>]*android:name="\\.'+name+'"[^>]*android:resizeableActivity="true"'));
  }
  assert.match(manifest,/@style\/Theme.ObserveLab.DayNight/);
  for(const variant of ['values','values-night']) assert.ok(existsSync(new URL('../app/src/main/res/'+variant+'/styles.xml',import.meta.url)));
});
