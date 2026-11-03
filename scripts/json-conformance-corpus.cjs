// Development-only JSON.parse oracle. Reuse JSON5 inputs to check rejection of extensions.
const fs = require('fs');
const original = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const cases = [], seen = new Set();
function canonical(value) {
  if (value === null) return { kind: 'null' };
  if (typeof value === 'number') {
    if (Number.isNaN(value)) return { kind: 'number', bits: 'NaN' };
    const bytes = Buffer.alloc(8); bytes.writeDoubleBE(value);
    return { kind: 'number', bits: bytes.toString('hex') };
  }
  if (typeof value === 'string' || typeof value === 'boolean') return { kind: typeof value, value };
  if (Array.isArray(value)) return { kind: 'array', values: value.map(canonical) };
  return { kind: 'object', entries: Object.keys(value).sort().map(key => [key, canonical(value[key])]) };
}
function add(source, category) {
  if (seen.has(source)) return;
  seen.add(source);
  const entry = { source, category };
  try { entry.expected = canonical(JSON.parse(source)); entry.valid = true; }
  catch (_) { entry.valid = false; }
  cases.push(entry);
}
for (const entry of original.cases) add(entry.source, entry.category);
for (let cp=0; cp<256; cp++) {
  const char=String.fromCharCode(cp);
  add('"a'+char+'b"', 'raw-string-character');
  add('"\\'+char+(char==='u'?'0000':'')+'"', 'escape-character');
  add(char+'1'+char, 'whitespace');
}
for (const source of ['0.0','-0.0','1e400','-1e400','1e-400','-1e-400','[1,]','{"a":1,}',
  '"\ud800"','"\udc00"','"\u2028\u2029"','"\\/"','"\\u0000"','truefalse','{"__proto__":1}',
  '{"x":1,"x":2}','\ufeff0','0\ufeff','[1/*c*/,2]','{"a"/*c*/:1}','{"a":/*c*/1}']) add(source,'boundary');
let state=0x8259abcd;
function random(n) { state^=state<<13; state^=state>>>17; state^=state<<5; return (state>>>0)%n; }
const atoms=[null,true,false,0,-0,1.25,1e100,1e-100,'text','\u0000\n\t"\\','\ud800','🌸','\u2028\u2029'];
for (let i=0;i<1500;i++) {
  const value={value:atoms[random(atoms.length)],index:i,items:[]};
  for(let j=0;j<1+random(6);j++)value.items.push(atoms[random(atoms.length)]);
  const text=JSON.stringify(value,null,i%3===0?2:0);
  add(text,'generated-json');
  const at=random(text.length);
  add(text.slice(0,at),'truncated-json');
  add(text.slice(0,at)+'/*x*/'+text.slice(at),'inserted-comment');
}
fs.writeFileSync(process.argv[3],JSON.stringify({reference:'JSON.parse',version:process.version,cases}));
console.log(JSON.stringify({reference:'JSON.parse',version:process.version,cases:cases.length,accepted:cases.filter(c=>c.valid).length}));
