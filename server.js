import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { classifyDevice } from './classifier.js';
import { estimateDistanceMeters, smoothRssi } from './ranging.js';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const publicRoot = path.join(projectRoot, 'public');
const logRoot = path.join(projectRoot, 'logs');
const config = JSON.parse(fs.readFileSync(path.join(projectRoot, 'config.json'), 'utf8'));
const demoMode = process.env.RADAR_DEMO === '1';
const port = Number(process.env.PORT ?? config.port ?? 3000);
const sessionSalt = process.env.RADAR_PSEUDONYM_SALT ?? crypto.randomBytes(24).toString('hex');
const devices = new Map();
const clients = new Set();
const lastLogged = new Map();
let scannerStatus = demoMode ? 'Starting demo' : 'Starting Bluetooth scan';
let scanner;

fs.mkdirSync(logRoot, { recursive: true });

function shortId(address) {
  return crypto.createHash('sha256').update(`${sessionSalt}:${address}`).digest('hex').slice(0, 8);
}

function angleFor(id) {
  return Number.parseInt(id.slice(0, 6), 16) % 360;
}

function safeDeviceName(device, fallbackLabel) {
  const value = String(device.alias || device.name || '').replace(/[\r\n\t]/g, ' ').trim();
  return value.slice(0, 48) || fallbackLabel;
}

function dateStamp(date = new Date()) {
  return date.toISOString().slice(0, 10);
}

function appendLog(entry) {
  const filename = path.join(logRoot, `detections-${dateStamp()}.jsonl`);
  fs.appendFile(filename, `${JSON.stringify(entry)}\n`, (error) => {
    if (error) scannerStatus = `Log error: ${error.message}`;
  });
}

function publicDevice(device) {
  return {
    id: device.id,
    label: device.label,
    advertisedName: device.advertisedName,
    category: device.category,
    categoryLabel: config.categories[device.category].label,
    color: config.categories[device.category].color,
    confidence: device.confidence,
    rssi: Math.round(device.rssi),
    distanceMeters: Number(device.distanceMeters.toFixed(1)),
    angleDegrees: device.angleDegrees,
    lastSeen: device.lastSeen,
    ...(config.storeRawBluetoothAddress ? { address: device.address } : {})
  };
}

function handleDevice(observation) {
  const now = Date.now();
  const id = shortId(observation.address);
  const previous = devices.get(id);
  const classification = classifyDevice(observation);
  const categoryConfig = config.categories[classification.category];
  const rssi = smoothRssi(previous?.rssi, observation.rssi);
  const distanceMeters = estimateDistanceMeters(
    rssi,
    observation.txPower ?? config.fallbackTxPower,
    config.pathLossExponent
  );
  const record = {
    id,
    address: observation.address,
    advertisedName: safeDeviceName(observation, categoryConfig.label),
    label: categoryConfig.label,
    category: classification.category,
    confidence: classification.confidence,
    rssi,
    distanceMeters,
    angleDegrees: previous?.angleDegrees ?? angleFor(id),
    lastSeen: now
  };
  devices.set(id, record);

  const logKey = `${id}:${record.category}`;
  const lastLogTime = lastLogged.get(logKey) ?? 0;
  const recognized = record.category !== 'other';
  if ((!config.logRecognizedOnly || recognized) && now - lastLogTime >= config.logRefreshSeconds * 1000) {
    appendLog({
      timestamp: new Date(now).toISOString(),
      event: previous ? 'seen' : 'detected',
      id,
      category: record.category,
      label: record.label,
      advertisedName: record.advertisedName,
      rssi: Math.round(record.rssi),
      estimatedDistanceMeters: Number(record.distanceMeters.toFixed(1)),
      confidence: record.confidence,
      ...(config.storeRawBluetoothAddress ? { address: observation.address } : {})
    });
    lastLogged.set(logKey, now);
  }
}

function snapshot() {
  const now = Date.now();
  const timeout = config.deviceTimeoutSeconds * 1000;
  for (const [id, device] of devices) {
    if (now - device.lastSeen > timeout) devices.delete(id);
  }
  return {
    timestamp: now,
    mode: demoMode ? 'demo' : 'live',
    status: scannerStatus,
    maximumRangeMeters: config.maximumDisplayRangeMeters,
    categories: config.categories,
    devices: [...devices.values()]
      .sort((a, b) => a.distanceMeters - b.distanceMeters)
      .map(publicDevice)
  };
}

function sendEvent(response, event, value) {
  response.write(`event: ${event}\ndata: ${JSON.stringify(value)}\n\n`);
}

function contentType(filename) {
  return ({
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.svg': 'image/svg+xml'
  })[path.extname(filename)] ?? 'application/octet-stream';
}

const server = http.createServer((request, response) => {
  const requestUrl = new URL(request.url, `http://${request.headers.host ?? 'localhost'}`);
  if (requestUrl.pathname === '/events') {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Content-Type-Options': 'nosniff'
    });
    response.write('retry: 1500\n\n');
    clients.add(response);
    sendEvent(response, 'snapshot', snapshot());
    request.on('close', () => clients.delete(response));
    return;
  }

  if (requestUrl.pathname === '/api/status') {
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify(snapshot()));
    return;
  }

  const relative = requestUrl.pathname === '/' ? 'index.html' : requestUrl.pathname.slice(1);
  const filename = path.resolve(publicRoot, relative);
  if (!filename.startsWith(`${publicRoot}${path.sep}`) || !fs.existsSync(filename) || fs.statSync(filename).isDirectory()) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found');
    return;
  }
  response.writeHead(200, {
    'Content-Type': contentType(filename),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  fs.createReadStream(filename).pipe(response);
});

setInterval(() => {
  const data = snapshot();
  for (const client of clients) sendEvent(client, 'snapshot', data);
}, 750).unref();

async function start() {
  try {
    const scannerModule = demoMode
      ? await import('./demo-scanner.js')
      : await import('./bluez-scanner.js');
    const factory = demoMode ? scannerModule.createDemoScanner : scannerModule.createBluezScanner;
    scanner = await factory({
      adapterName: config.adapter,
      onDevice: handleDevice,
      onStatus: (message) => { scannerStatus = message; }
    });
  } catch (error) {
    scannerStatus = `Scanner unavailable: ${error.message}`;
    console.error(scannerStatus);
  }

  server.listen(port, '0.0.0.0', () => {
    console.log(`Pi Radar ${demoMode ? '(demo) ' : ''}running at http://localhost:${port}`);
  });
}

async function stop() {
  await scanner?.stop();
  for (const client of clients) client.end();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 2000).unref();
}

process.on('SIGINT', stop);
process.on('SIGTERM', stop);
start();