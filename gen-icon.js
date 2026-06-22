const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const outDir = path.join(__dirname, 'resources');
const publicDir = path.join(__dirname, 'frontend');

if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

// Simple, clean SVG icon - solid purple bg with white music note
// Square (no rounded corners) for Android compatibility
const svg = `<svg width="1024" height="1024" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1024" y2="1024">
      <stop offset="0%" stop-color="#7c3aed"/>
      <stop offset="100%" stop-color="#a855f7"/>
    </linearGradient>
  </defs>
  <rect width="1024" height="1024" fill="url(#g)"/>
  <g fill="white">
    <ellipse cx="280" cy="640" rx="110" ry="90"/>
    <ellipse cx="700" cy="720" rx="110" ry="90"/>
    <rect x="370" y="160" width="65" height="620" rx="30"/>
    <rect x="630" y="260" width="65" height="620" rx="30"/>
    <rect x="370" y="160" width="380" height="65" rx="30"/>
  </g>
</svg>`;

async function genIcons() {
  const buf = await sharp(Buffer.from(svg)).png().toBuffer();
  const sizes = [1024, 512, 192, 144];
  for (const s of sizes) {
    const f = s === 1024 ? path.join(outDir, 'icon.png') : path.join(publicDir, `icon-${s}.png`);
    await sharp(buf).resize(s, s).png().toFile(f);
    console.log(`${path.basename(f)} (${s}x${s}) generated`);
  }
  // Also generate maskable icon (icon with padding for Android adaptive icons)
  const maskSvg = `<svg width="1024" height="1024" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="g2" x1="0" y1="0" x2="1024" y2="1024">
        <stop offset="0%" stop-color="#7c3aed"/>
        <stop offset="100%" stop-color="#a855f7"/>
      </linearGradient>
    </defs>
    <rect width="1024" height="1024" fill="url(#g2)"/>
    <g fill="white" transform="translate(150,140) scale(0.7)">
      <ellipse cx="280" cy="640" rx="110" ry="90"/>
      <ellipse cx="700" cy="720" rx="110" ry="90"/>
      <rect x="370" y="160" width="80" height="620" rx="30"/>
      <rect x="630" y="260" width="80" height="620" rx="30"/>
      <rect x="370" y="160" width="380" height="80" rx="30"/>
    </g>
  </svg>`;
  const maskBuf = await sharp(Buffer.from(maskSvg)).resize(512, 512).png().toFile(path.join(publicDir, 'icon-maskable.png'));
  console.log('icon-maskable.png (512x512) generated');
}

genIcons().catch(e => { console.error(e); process.exit(1); });
