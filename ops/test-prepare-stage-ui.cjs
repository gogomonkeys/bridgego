'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../backend/src/main/resources/static/initial-cutover/operator.js'),'utf8');
const section=source.slice(source.indexOf('  const prepareStageLabels'),source.indexOf('  function status('));
const codes=['OPERATOR_AUTH','LEGACY_OPEN','LEGACY_FETCH','LEGACY_PARSE','LEGACY_PARSE_ROOT','LEGACY_PARSE_ROOT_PROPERTIES','LEGACY_PARSE_COMMENTS','LEGACY_PARSE_STYLE','LEGACY_PARSE_SHEET','LEGACY_PARSE_DIMENSION','LEGACY_PARSE_CELL','LEGACY_PARSE_CONTROL','LEGACY_PARSE_BUDGET','CUTOVER_ASSESSMENT','PREPARE_GUARD'];
for(const code of [...codes,'__proto__','constructor','SECRET_TOKEN:<script>',{},null,undefined]){
 const records=[],element={textContent:''};const context={record:x=>records.push(x),$:()=>element,code};vm.createContext(context);vm.runInContext(section+'\nshowPrepareFailure(code);',context);
 if(codes.includes(code)){assert.deepEqual(records,['PREPARE_STAGE_'+code]);assert.ok(element.textContent.includes(code));assert.ok(element.textContent.includes('非確定原因'));}
 else {assert.deepEqual(records,[]);assert.equal(element.textContent,'');}
}
assert.ok(source.includes("if(path==='prepare'&&!write)showPrepareFailure(data.prepareStage)"));
console.log('prepare stage UI whitelist tests passed');
