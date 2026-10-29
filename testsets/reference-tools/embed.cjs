// Usage: node embed.cjs <template.html> <data.json> <out.html>
const fs = require('fs');
const [tpl, dataPath, out] = process.argv.slice(2);
const html = fs.readFileSync(tpl, 'utf8');
const json = JSON.stringify(JSON.parse(fs.readFileSync(dataPath, 'utf8')), null, 2).split('<').join('\\u003c');
const open = '<script type="application/json" id="arc-data">';
const start = html.indexOf(open);
if (start < 0 || html.indexOf(open, start + 1) >= 0) throw new Error('data element not found exactly once');
const bodyStart = start + open.length;
const end = html.indexOf('</script>', bodyStart);
fs.writeFileSync(out, html.slice(0, bodyStart) + '\n' + json + '\n' + html.slice(end));
console.log('wrote', out, fs.statSync(out).size, 'bytes');
