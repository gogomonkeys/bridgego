import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const browser = await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
for (const width of [390,1280]) {
 const page=await browser.newPage({viewport:{width,height:844}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',r=>{
  const u=new URL(r.request().url());
  if(u.origin!=='http://127.0.0.1:5198') return r.abort();
  if(u.pathname.startsWith('/api/')) return r.fulfill({json:u.pathname==='/api/rooms'?[]:{enabled:false,statisticsEnabled:false}});
  return r.continue();
 });
 await page.goto('http://127.0.0.1:5198/#help');
 await page.getByRole('heading',{name:'快速開始'}).waitFor();
 assert.equal(await page.locator('.help details[open]').count(),0);
 for(const label of ['操作倒數、斷線與牌局紀錄','保存期限、刪除申請與限制','僅限測試模式的補充說明']) {
  const summary=page.locator('summary').filter({hasText:label});
  await summary.click(); assert.equal(await summary.evaluate(e=>e.parentElement.open),true);
  await summary.focus();await page.keyboard.press('Enter');assert.equal(await summary.evaluate(e=>e.parentElement.open),false);
 }
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
 await page.screenshot({path:`/tmp/bridge-help-${width}.png`,fullPage:true});
 await page.getByRole('button',{name:'回到等候室'}).click();assert.equal(new URL(page.url()).hash,'#room');
 await page.goBack();await page.getByRole('heading',{name:'快速開始'}).waitFor();
 assert.deepEqual(errors,[]);await page.close();
 console.log(`${width}px: help, collapsed details, mouse/keyboard toggles, return/back, no overflow/error PASS`);
}
await browser.close();
