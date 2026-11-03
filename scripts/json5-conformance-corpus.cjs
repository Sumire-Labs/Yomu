// Development-only oracle generator. Supply the extracted official json5 npm package.
const fs = require('fs');
const path = require('path');
const JSON5 = require(path.resolve(process.argv[2], 'lib'));
const version = require(path.resolve(process.argv[2], 'package.json')).version;
const cases = [];
const seen = new Set();
function canonical(value) {
  if (value === null) return { kind: 'null' };
  if (typeof value === 'number') {
    if (Number.isNaN(value)) return { kind: 'number', bits: 'NaN' };
    const data = Buffer.alloc(8); data.writeDoubleBE(value);
    return { kind: 'number', bits: data.toString('hex') };
  }
  if (typeof value === 'string' || typeof value === 'boolean') return { kind: typeof value, value };
  if (Array.isArray(value)) return { kind: 'array', values: value.map(canonical) };
  return { kind: 'object', entries: Object.keys(value).sort().map(k => [k, canonical(value[k])]) };
}
const originalWarn = console.warn;
console.warn = () => {};
function add(source, category) {
  if (seen.has(source)) return;
  seen.add(source);
  const entry = { source, category };
  try { entry.expected = canonical(JSON5.parse(source)); entry.valid = true; }
  catch (_) { entry.valid = false; }
  cases.push(entry);
}
for (let c = 0; c < 256; c++) {
  const char = String.fromCharCode(c);
  for (const quote of ["'", '"']) {
    add(quote + char + quote, 'raw-latin1-string');
    add(quote + '\\' + char + quote, 'latin1-escape');
  }
  add('{' + char + ':1}', 'identifier-start');
  add('{a' + char + ':1}', 'identifier-part');
  add('{\\u' + c.toString(16).padStart(4, '0') + ':1}', 'escaped-identifier');
}
for (const cp of [0x1680,0x180e,0x2000,0x2001,0x2002,0x2003,0x2004,0x2005,0x2006,0x2007,0x2008,0x2009,0x200a,0x2028,0x2029,0x202f,0x205f,0x3000,0xfeff]) {
  const c = String.fromCodePoint(cp);
  add(c + '1' + c, 'whitespace');
  add("'a" + c + "b'", 'unicode-string');
  add("'a\\" + c + "b'", 'unicode-escape');
}
for (const key of ['日本語','αβ','a\u200c','a\u200d','\u200ca','\u200da','𐐀','Ꭓ','𞤀','\\ud801\\udc00']) {
  add('{' + key + ':1}', 'unicode-identifier');
}
for (const n of ['0','00','01','.0','0.','1.e2','1e','0x','0xff','0XFF','0x1p1','1e400','1e-400','9223372036854775808','NaN','Infinity']) {
  for (const sign of ['', '+', '-']) {
    add(sign + n, 'number');
    add('[' + sign + n + ',]', 'number-array');
    add(sign + n + 'x', 'number-boundary');
  }
}
for (const source of ['','/*','//x','[1,,2]','[,]','{a:1,,}','{a:1 b:2}',"['\\0','\\01']","['\\x41','\\x4g']",'true false','nullx','undefined','{__proto__:1}']) add(source,'boundary');
const fixtureRoot = path.resolve('src/test/resources/json5-tests');
function fixtures(dir) {
  for (const item of fs.readdirSync(dir,{withFileTypes:true})) {
    const file=path.join(dir,item.name);
    if(item.isDirectory()) fixtures(file);
    else if(/\.(json5?|js|txt)$/.test(item.name)) add(fs.readFileSync(file,'utf8'),'official-fixture');
  }
}
fixtures(fixtureRoot);
let state=0x5eed1234;
function random(n) { state^=state<<13;state^=state>>>17;state^=state<<5;return (state>>>0)%n; }
const atoms=["'text'","'\\u3042\\n'",'null','true','false','-0','+.5','0xff','NaN','Infinity','[1,2,]','{x:1,}'];
for(let i=0;i<4000;i++) {
  let source='[ ';
  const count=1+random(8);
  for(let j=0;j<count;j++) source+=atoms[random(atoms.length)]+',';
  source+=']';
  add(source,'generated');
  const at=random(source.length);
  add(source.slice(0,at),'truncated');
  add(source.slice(0,at)+'\\'+source.slice(at),'mutated');
}
console.warn=originalWarn;
fs.writeFileSync(process.argv[3], JSON.stringify({ reference:'json5',version,cases }));
console.log(JSON.stringify({reference:'json5',version,cases:cases.length,accepted:cases.filter(x=>x.valid).length}));
