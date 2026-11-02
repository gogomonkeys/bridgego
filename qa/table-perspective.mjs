// Actual TableSeats component rendered as static HTML; not browser/layout validation.
import {build} from '../frontend/node_modules/esbuild/lib/main.js';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
const work=await mkdtemp(join(tmpdir(),'bridge-table-qa-'));
try {
 const result=await build({stdin:{contents:String.raw`
 import React from 'react';
 import {renderToStaticMarkup} from 'react-dom/server';
 import TableSeats from './src/TableSeats';
 import assert from 'node:assert/strict';
 const results=[];
 for(let own=0;own<4;own++){
  const room={phase:'TRICK_RESULT',game:{play:{completedTricks:[{winnerSeat:own},{winnerSeat:(own+2)%4},{winnerSeat:own}]}},you:{seat:own},hostPlayerId:'p0',players:[0,1,2,3].map(seat=>({seat,playerId:'p'+seat,displayName:'Synthetic '+seat,connected:true}))};
  const html=renderToStaticMarkup(<TableSeats room={room} turnSeat={(own+1)%4} winnerSeat={own}/>);
  const articles=[...html.matchAll(/<article([^>]*)>([\s\S]*?)<\/article>/g)];
  assert.equal(articles.length,4);
  for(let seat=0;seat<4;seat++){
   const [_,attrs,body]=articles.find(a=>a[1].includes('data-seat="'+seat+'"'));
   assert(attrs.includes('position-'+['bottom','left','top','right'][(seat-own+4)%4]));
   assert(attrs.includes(['北 N','東 E','南 S','西 W'][seat]));
   assert(body.includes(seat===own?'你':(seat-own+4)%4===2?'隊友':'對手'));
   assert.equal(attrs.includes('active-turn'),seat===(own+1)%4);
   assert.equal(attrs.includes('trick-winner'),seat===own);
   const count=seat===own?2:seat===(own+2)%4?1:0;
   assert(body.includes('本副贏得 '+count+' 墩')); 
  }
  results.push({case:'actual static table preserves seat identity from viewer '+own,status:'PASS'});
 }
 console.log(JSON.stringify(results));
 `,resolveDir:resolve('frontend'),loader:'tsx'},bundle:true,platform:'node',format:'cjs',write:false,jsx:'automatic'});
 const file=join(work,'check.cjs');await writeFile(file,result.outputFiles[0].text);
 const run=spawnSync(process.execPath,[file],{encoding:'utf8'});if(run.status!==0)throw Error(run.stderr);
 const results=JSON.parse(run.stdout);await writeFile(new URL('./table-perspective-results.json',import.meta.url),JSON.stringify(results,null,2)+'\n');console.log(results);
}finally{await rm(work,{recursive:true,force:true});}
