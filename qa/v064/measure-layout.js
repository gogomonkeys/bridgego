(body) => {
  const doc=body.ownerDocument, root=doc.documentElement;
  const visible=e=>{const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;};
  const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};
  const outer=e=>{
    const r=rect(e),s=getComputedStyle(e);let l=0,t=0,b=0,rr=0;
    if(s.outlineStyle!=='none'){const v=Math.max(0,Number(s.outlineWidth.replace("px",""))+Number(s.outlineOffset.replace("px","")));l=t=b=rr=v;}
    for(const sh of s.boxShadow.split(/,(?![^()]*\))/)){
      if(sh.includes('inset')||sh==='none')continue;
      const clean=sh.replace(/rgba?\([^)]*\)/g,'').trim(),nums=(clean.match(/-?[\d.]+px/g)||[]).map(n=>Number(n.replace("px","")));
      if(nums.length<2)continue;const [x,y,blur=0,spread=0]=nums,ext=Math.max(0,blur+spread);
      l=Math.max(l,ext-x);rr=Math.max(rr,ext+x);t=Math.max(t,ext-y);b=Math.max(b,ext+y);
    }
    return {...r,left:r.left-l,right:r.right+rr,top:r.top-t,bottom:r.bottom+b,ext:{l,t,r:rr,b}};
  };
  const all=sel=>[...body.querySelectorAll(sel)].filter(visible);
  const item=e=>({class:e.className,text:(e.innerText||'').slice(0,150),r:rect(e),v:outer(e),font:getComputedStyle(e).fontSize,outline:getComputedStyle(e).outline,outlineOffset:getComputedStyle(e).outlineOffset,shadow:getComputedStyle(e).boxShadow});
  const seats=all('.table-player').map(item),cards=all('.played-card').map(item),hands=all('.hand-card').map(item),boards=all('.match-table,.play-surface').map(item);
  const gap=(a,b)=>{const dx=Math.max(b.left-a.right,a.left-b.right),dy=Math.max(b.top-a.bottom,a.top-b.bottom);return Math.max(dx,dy);};
  const overlap=[];
  for(const s of seats)for(const c of cards){const g=gap(s.v,c.v);if(g<0)overlap.push({seat:s.class,card:c.class,visualGap:g,domGap:gap(s.r,c.r)});}
  const gaps=seats.flatMap(s=>cards.map(c=>({seat:s.class,card:c.class,visualGap:gap(s.v,c.v),domGap:gap(s.r,c.r)})));
  const targets=all('.compact-bids button,.compact-bids select,.hand-confirm button,.privilege-controls button').map(item);
  const label=all('.bid-mobile-selectors label span').map(item);
  const glyphFailures=[];for(const c of all('.played-card')){const cr=rect(c);for(const g of [...c.querySelectorAll('.played-suit,.played-rank')]){const r=rect(g);if(r.left<cr.left||r.right>cr.right||r.top<cr.top||r.bottom>cr.bottom)glyphFailures.push({card:c.className,glyph:g.textContent,rect:r,cardRect:cr});}}
  const docks=all('.match-action-dock,.phase-action-dock,.table-action-dock').map(item);const table=boards[0];const seatContainment=table?seats.filter(s=>s.r.bottom>table.r.bottom||s.r.top<table.r.top||s.r.left<table.r.left||s.r.right>table.r.right).map(s=>({seat:s.class,r:s.r,table:table.r})):[];const dockOverlap=[];for(const seat of seats)for(const dock of docks)if(gap(seat.v,dock.r)<0)dockOverlap.push({seat:seat.class,dock:dock.class,gap:gap(seat.v,dock.r)});const bodyText=body.innerText;
  return {docks,dockOverlap,seatContainment,viewport:{w:root.clientWidth,h:root.clientHeight},scroll:{w:root.scrollWidth,h:root.scrollHeight,bodyW:body.scrollWidth,bodyH:body.scrollHeight},seats,cards,hands,boards,targets,label,gaps,overlap,glyphFailures,portraits:all('.animal-avatar,.player-avatar,.seat-portrait img').map(item),status:all('.match-status-bar,.match-information,.operation-clock,.table-status-bar').map(item),handPanel:all('.own-hand-panel').map(item),confirm:all('.hand-confirm').map(item),bid:all('.compact-bids').map(item),header:all('header').map(item),text:bodyText.slice(0,6500),required:{privilege:bodyText.includes('特權'),timer:!!body.querySelector('.operation-clock'),hand:hands.length},oldTerms:bodyText.match(/本副|各副|第\s*\d+\s*墩|收墩|贏墩|\/\s*3\s*副|LOCAL PROTOTYPE|THREE-DEAL MATCH|本機隔離測試|獨立接手版|不設夢家/g)||[]};
}
