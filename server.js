const http = require('http');
const fs = require('fs');
const path = require('path');

const root = __dirname;
const dataPath = path.join(root, 'data.json');
const staticDir = path.join(root, 'public');

const seed = {
  assets: [
    { id: 1, name: 'Canon EOS 200D Camera', category: 'Media', code: 'MDC-014', condition: 'Good', location: 'Media Room', holder: 'Aarav Mehta', status: 'Assigned' },
    { id: 2, name: 'JBL PartyBox Speaker', category: 'Events', code: 'EVT-008', condition: 'Excellent', location: 'Store Room A', holder: 'Unassigned', status: 'Available' },
    { id: 3, name: 'Robotics Toolkit', category: 'Technical', code: 'TEC-021', condition: 'Fair', location: 'Innovation Lab', holder: 'Ishita Rao', status: 'Assigned' },
    { id: 4, name: 'Club Banner Set', category: 'Branding', code: 'BRD-003', condition: 'Good', location: 'Store Room B', holder: 'Unassigned', status: 'Available' }
  ],
  handovers: [
    { id: 1, assetId: 1, from: 'Aarav Mehta', to: 'Diya Nair', club: 'Photography Club', dueDate: '2026-09-25', notes: 'Return with charged battery and SD card.', condition: 'Good', status: 'Pending approval', createdAt: '2026-09-21' },
    { id: 2, assetId: 3, from: 'Ishita Rao', to: 'Kabir Shah', club: 'Robotics Club', dueDate: '2026-09-19', notes: 'Kit is complete except one Allen key.', condition: 'Fair', status: 'Completed', createdAt: '2026-09-15' }
  ]
};

function readData() {
  if (!fs.existsSync(dataPath)) fs.writeFileSync(dataPath, JSON.stringify(seed, null, 2));
  return JSON.parse(fs.readFileSync(dataPath, 'utf8'));
}
function writeData(data) { fs.writeFileSync(dataPath, JSON.stringify(data, null, 2)); }
function json(res, code, body) { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); }
function body(req) { return new Promise((resolve, reject) => { let raw = ''; req.on('data', c => raw += c); req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch (e) { reject(e); } }); }); }
function serveFile(res, file) {
  const extension = path.extname(file);
  const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript' };
  fs.readFile(file, (err, content) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': `${types[extension] || 'text/plain'}; charset=utf-8` }); res.end(content);
  });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  try {
    if (url.pathname === '/api/dashboard' && req.method === 'GET') {
      const db = readData();
      const total = db.assets.length, available = db.assets.filter(a => a.status === 'Available').length;
      return json(res, 200, { total, available, assigned: total - available, pending: db.handovers.filter(h => h.status === 'Pending approval').length, recent: db.handovers.slice().sort((a,b) => b.id-a.id).slice(0,5) });
    }
    if (url.pathname === '/api/assets' && req.method === 'GET') return json(res, 200, readData().assets);
    if (url.pathname === '/api/assets' && req.method === 'POST') {
      const db = readData(), item = await body(req);
      if (!item.name || !item.code) return json(res, 400, { error: 'Asset name and ID are required.' });
      if (db.assets.some(a => a.code.toLowerCase() === item.code.toLowerCase())) return json(res, 400, { error: 'That asset ID already exists.' });
      const asset = { id: Date.now(), name: item.name.trim(), category: item.category || 'Other', code: item.code.trim(), condition: item.condition || 'Good', location: item.location || 'Not specified', holder: 'Unassigned', status: 'Available' };
      db.assets.push(asset); writeData(db); return json(res, 201, asset);
    }
    if (url.pathname === '/api/handovers' && req.method === 'GET') return json(res, 200, readData().handovers);
    if (url.pathname === '/api/handovers' && req.method === 'POST') {
      const db = readData(), item = await body(req), asset = db.assets.find(a => a.id === Number(item.assetId));
      if (!asset || !item.from || !item.to || !item.dueDate) return json(res, 400, { error: 'Complete all required handover fields.' });
      if (asset.status !== 'Available' && asset.holder !== item.from) return json(res, 400, { error: 'The chosen asset is not held by the stated outgoing member.' });
      const handover = { id: Date.now(), assetId: asset.id, from: item.from.trim(), to: item.to.trim(), club: item.club || 'General', dueDate: item.dueDate, notes: item.notes || '', condition: item.condition || asset.condition, status: 'Pending approval', createdAt: new Date().toISOString().slice(0,10) };
      db.handovers.push(handover); writeData(db); return json(res, 201, handover);
    }
    const match = url.pathname.match(/^\/api\/handovers\/(\d+)\/approve$/);
    if (match && req.method === 'PATCH') {
      const db = readData(), handover = db.handovers.find(h => h.id === Number(match[1]));
      if (!handover || handover.status !== 'Pending approval') return json(res, 404, { error: 'Pending handover not found.' });
      const asset = db.assets.find(a => a.id === handover.assetId);
      handover.status = 'Completed'; asset.holder = handover.to; asset.status = 'Assigned'; asset.condition = handover.condition;
      writeData(db); return json(res, 200, handover);
    }
    const requested = url.pathname === '/' ? 'index.html' : url.pathname.replace(/^\//, '');
    const file = path.resolve(staticDir, requested);
    if (!file.startsWith(staticDir)) { res.writeHead(403); return res.end('Forbidden'); }
    serveFile(res, file);
  } catch (error) { json(res, 500, { error: error.message || 'Server error' }); }
}).listen(3000, () => console.log('Club Asset Handover System: http://localhost:3000'));
