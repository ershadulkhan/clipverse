// Generates Clipverse's PNG icons with no image tooling (pure Node + zlib).
// Design: a crimson rounded square with the classic "copy" mark — a white page in front of an
// outlined page behind it, offset diagonally. Minimal and readable down to 16 px.
// Usage: node scripts/gen-icons.mjs [outDir] [sizes] [fileNamePattern]
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
};

/** Signed distance to a rounded rectangle centred at (cx, cy) with half-size (hx, hy), radius r. */
function sdRoundRect(u, v, cx, cy, hx, hy, r) {
  const qx = Math.abs(u - cx) - (hx - r);
  const qy = Math.abs(v - cy) - (hy - r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}

const ACCENT = [235 / 255, 0, 40 / 255];
const WHITE = [1, 1, 1];

/** Colour of one sample point in unit space [0,1]². Returns [r,g,b,a] in 0..1. */
function sample(u, v) {
  if (sdRoundRect(u, v, 0.5, 0.5, 0.5, 0.5, 0.235) > 0) return [0, 0, 0, 0];
  // Back page: outline only. Front page: solid white, with a crimson gap around it so the
  // two pages read as separate sheets even at 16 px.
  const back = sdRoundRect(u, v, 0.425, 0.425, 0.17, 0.2, 0.055);
  const front = sdRoundRect(u, v, 0.575, 0.575, 0.17, 0.2, 0.055);
  if (front <= 0) return [...WHITE, 1];
  if (front <= 0.04) return [...ACCENT, 1];
  if (Math.abs(back) <= 0.024) return [...WHITE, 1];
  return [...ACCENT, 1];
}

function icon(size) {
  const S = 4; // 4×4 supersampling for smooth edges
  const px = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let sy = 0; sy < S; sy++) for (let sx = 0; sx < S; sx++) {
      const [cr, cg, cb, ca] = sample((x + (sx + 0.5) / S) / size, (y + (sy + 0.5) / S) / size);
      r += cr * ca; g += cg * ca; b += cb * ca; a += ca;
    }
    const i = (y * size + x) * 4;
    px[i] = a ? Math.round((r / a) * 255) : 0;
    px[i + 1] = a ? Math.round((g / a) * 255) : 0;
    px[i + 2] = a ? Math.round((b / a) * 255) : 0;
    px[i + 3] = Math.round((a / (S * S)) * 255);
  }
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) px.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

const out = process.argv[2] || 'public/icons';
const sizes = (process.argv[3] || '16,32,48,128').split(',').map(Number);
const pattern = process.argv[4] || 'icon{size}.png';
mkdirSync(out, { recursive: true });
for (const s of sizes) writeFileSync(`${out}/${pattern.replace('{size}', s)}`, icon(s));
console.log(`icons written to ${out}: ${sizes.join(', ')}`);
