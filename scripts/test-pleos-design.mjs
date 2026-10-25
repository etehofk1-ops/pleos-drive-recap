import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,readdirSync} from 'node:fs';
const read=p=>readFileSync(new URL(p,import.meta.url),'utf8').replace(/^\uFEFF/,'');

test('all judgement icons resolve to hand-authored 48dp monochrome vectors on the 24 grid',()=>{
  const ui=read('../app/src/main/java/lab/pleos/observe/VehicleUi.kt');
  const names=[...new Set([...ui.matchAll(/R\.drawable\.(ic_\w+)/g)].map(m=>m[1]))];
  assert.equal(names.length,14);
  for(const name of names){
    const xml=read('../app/src/main/res/drawable/'+name+'.xml');
    for(const [attr,value] of Object.entries({width:'48dp',height:'48dp',viewportWidth:'24',viewportHeight:'24',strokeWidth:'1.8',strokeLineCap:'round',strokeLineJoin:'round'}))
      assert.match(xml,new RegExp('android:'+attr+'="'+value+'"'),name+' '+attr);
    assert.match(xml,/android:pathData="[^"]+"/);assert.doesNotMatch(xml,/<bitmap|https?:\/\/(?!schemas\.android)/);
  }
});

test('both system themes use source neutral colors, platform sans-serif, and no forbidden branding',()=>{
  for(const [variant,bg,fg] of [['values','#F7F8FA','#131417'],['values-night','#1D1E21','#FFFFFF']]){
    const xml=read('../app/src/main/res/'+variant+'/styles.xml');
    assert.ok(xml.includes('name="android:windowBackground">'+bg));
    assert.ok(xml.includes('name="android:textColorPrimary">'+fg));
    assert.ok(xml.includes('name="android:fontFamily">sans-serif'));
    assert.doesNotMatch(xml,/#(?:5A46FA|6A57FF|596BFF|805100|FFD084)/i);
  }
  function files(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(x=>x.isDirectory()?files(new URL(x.name+'/',dir)):[new URL(x.name,dir)]);}
  const resources=files(new URL('../app/src/main/res/',import.meta.url));
  assert.ok(resources.every(p=>! /\.(ttf|otf|woff2?)$/i.test(p.pathname)),'no bundled font');
});

test('design change preserves non-DO, resize recreation, release network and debug-only demo boundaries',()=>{
  const main=read('../app/src/main/AndroidManifest.xml');
  assert.doesNotMatch(main,/android:(screenOrientation|configChanges)=|<meta-data[^>]*distractionOptimized/);
  assert.match(main,/android:resizeableActivity="true"/);
  const debug=read('../app/src/debug/AndroidManifest.xml');
  assert.match(debug,/DemoNaviReceiver/);assert.doesNotMatch(main,/DemoNaviReceiver/);
});
