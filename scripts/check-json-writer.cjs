// Verify actual Java writer output with the independent JavaScript JSON.parse implementation.
const fs=require('fs');
const corpus=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
function canonical(value) {
  if(value===null)return {kind:'null'};
  if(typeof value==='number') {
    if(Number.isNaN(value))return {kind:'number',bits:'NaN'};
    const bytes=Buffer.alloc(8);bytes.writeDoubleBE(value);
    return {kind:'number',bits:bytes.toString('hex')};
  }
  if(typeof value==='string'||typeof value==='boolean')return {kind:typeof value,value};
  if(Array.isArray(value))return {kind:'array',values:value.map(canonical)};
  return {kind:'object',entries:Object.keys(value).sort().map(key=>[key,canonical(value[key])])};
}
let count=0;
for(const entry of corpus.cases) {
  const actual=canonical(JSON.parse(entry.source));
  if(JSON.stringify(actual)!==JSON.stringify(entry.expected))throw new Error('Writer value mismatch at case '+count);
  count++;
}
const report={reference:'JSON.parse',version:process.version,java:corpus.java,bigDecimals:corpus.bigDecimals,cases:count,skippedNonFinite:corpus.skippedNonFinite,differences:0};
if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify(report));
console.log(JSON.stringify(report));
