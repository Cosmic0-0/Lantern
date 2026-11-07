import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
const dir = process.argv[2], out = process.argv[3];
const files = readdirSync(dir).filter(f => f.endsWith('.wav')).sort();
let fmt; const parts = [];
for (const f of files) {
  const b = readFileSync(dir + '/' + f);
  let p = 12;
  while (p + 8 <= b.length) {
    const id = b.toString('latin1', p, p + 4), len = b.readUInt32LE(p + 4);
    if (id === 'fmt ') fmt = b.subarray(p, p + 8 + len);
    if (id === 'data') parts.push(b.subarray(p + 8, p + 8 + len));
    p += 8 + len + (len % 2);
  }
}
const data = Buffer.concat(parts);
const dh = Buffer.alloc(8); dh.write('data', 0, 'latin1'); dh.writeUInt32LE(data.length, 4);
const rh = Buffer.alloc(12); rh.write('RIFF', 0, 'latin1'); rh.writeUInt32LE(4 + fmt.length + 8 + data.length, 4); rh.write('WAVE', 8, 'latin1');
writeFileSync(out, Buffer.concat([rh, fmt, dh, data]));
const rate = fmt.readUInt32LE(12), bytes = fmt.readUInt16LE(22) / 8 * fmt.readUInt16LE(10);
console.log('joined', files.length, 'seconds', (data.length / rate / bytes).toFixed(1));
