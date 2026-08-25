const canvas = document.querySelector('#radar');
const context = canvas.getContext('2d');
const elements = {
  mode: document.querySelector('#mode'),
  status: document.querySelector('#status'),
  statusDot: document.querySelector('#status-dot'),
  count: document.querySelector('#device-count'),
  nearest: document.querySelector('#nearest-distance'),
  list: document.querySelector('#device-list'),
  legend: document.querySelector('#legend'),
  clock: document.querySelector('#clock'),
  outer: document.querySelector('#range-outer'),
  mid: document.querySelector('#range-mid'),
  inner: document.querySelector('#range-inner')
};

let state = { devices: [], maximumRangeMeters: 15, categories: {} };
let sweepAngle = 0;
let lastFrame = performance.now();

function resizeCanvas() {
  const rect = canvas.getBoundingClientRect();
  const scale = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.max(1, Math.round(rect.width * scale));
  canvas.height = Math.max(1, Math.round(rect.height * scale));
  context.setTransform(scale, 0, 0, scale, 0, 0);
}

function polarPoint(cx, cy, radius, degrees) {
  const angle = degrees * Math.PI / 180;
  return { x: cx + Math.cos(angle) * radius, y: cy + Math.sin(angle) * radius };
}

function drawGrid(cx, cy, radius) {
  context.strokeStyle = 'rgba(101, 255, 122, 0.17)';
  context.lineWidth = 1;
  for (let ring = 1; ring <= 3; ring += 1) {
    context.beginPath();
    context.arc(cx, cy, radius * ring / 3, 0, Math.PI * 2);
    context.stroke();
  }
  for (let degrees = 0; degrees < 360; degrees += 45) {
    const end = polarPoint(cx, cy, radius, degrees);
    context.beginPath();
    context.moveTo(cx, cy);
    context.lineTo(end.x, end.y);
    context.stroke();
  }
}

function drawSweep(cx, cy, radius) {
  const start = (sweepAngle - 34) * Math.PI / 180;
  const end = sweepAngle * Math.PI / 180;
  const gradient = context.createRadialGradient(cx, cy, 0, cx, cy, radius);
  gradient.addColorStop(0, 'rgba(101,255,122,.20)');
  gradient.addColorStop(1, 'rgba(101,255,122,.03)');
  context.fillStyle = gradient;
  context.beginPath();
  context.moveTo(cx, cy);
  context.arc(cx, cy, radius, start, end);
  context.closePath();
  context.fill();
  const edge = polarPoint(cx, cy, radius, sweepAngle);
  context.strokeStyle = 'rgba(101,255,122,.85)';
  context.beginPath();
  context.moveTo(cx, cy);
  context.lineTo(edge.x, edge.y);
  context.stroke();
}

function drawDevices(cx, cy, radius) {
  for (const device of state.devices) {
    const rangeRatio = Math.min(device.distanceMeters / state.maximumRangeMeters, 1);
    const point = polarPoint(cx, cy, Math.max(16, radius * rangeRatio), device.angleDegrees);
    const sweepDifference = ((sweepAngle - device.angleDegrees + 540) % 360) - 180;
    const glow = Math.max(0.45, 1 - Math.abs(sweepDifference) / 80);
    context.save();
    context.shadowBlur = 8 + glow * 14;
    context.shadowColor = device.color;
    context.fillStyle = device.color;
    context.globalAlpha = 0.62 + glow * 0.38;
    context.beginPath();
    context.arc(point.x, point.y, 3.6 + glow * 1.8, 0, Math.PI * 2);
    context.fill();
    context.shadowBlur = 0;
    context.font = '8px monospace';
    context.fillStyle = '#dffff0';
    context.fillText(device.label, point.x + 8, point.y - 6);
    context.restore();
  }
}

function renderFrame(now) {
  const elapsed = Math.min(50, now - lastFrame);
  lastFrame = now;
  sweepAngle = (sweepAngle + elapsed * 0.055) % 360;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const cx = width / 2;
  const cy = height / 2;
  const radius = Math.max(20, Math.min(width, height) * 0.46);
  context.clearRect(0, 0, width, height);
  drawGrid(cx, cy, radius);
  drawSweep(cx, cy, radius);
  drawDevices(cx, cy, radius);
  requestAnimationFrame(renderFrame);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  })[character]);
}

function renderPanel() {
  elements.mode.textContent = state.mode === 'demo' ? 'DEMO MODE' : 'LIVE SCAN';
  elements.status.textContent = state.status;
  elements.statusDot.style.background = state.status.startsWith('Scanner unavailable') ? '#ff3b52' : '#65ff7a';
  elements.count.textContent = state.devices.length;
  elements.nearest.textContent = state.devices.length ? `${state.devices[0].distanceMeters.toFixed(1)}m` : 'â';
  elements.outer.textContent = `${state.maximumRangeMeters} m`;
  elements.mid.textContent = `${Math.round(state.maximumRangeMeters * 2 / 3)} m`;
  elements.inner.textContent = `${Math.round(state.maximumRangeMeters / 3)} m`;

  elements.legend.innerHTML = Object.values(state.categories).map((category) => `
    <span class="legend-item">
      <i class="legend-swatch" style="color:${category.color};background:${category.color}"></i>
      ${escapeHtml(category.label)}
    </span>
  `).join('');

  elements.list.innerHTML = state.devices.length ? state.devices.map((device) => `
    <div class="device-row">
      <i class="device-pip" style="color:${device.color};background:${device.color}"></i>
      <div class="device-copy">
        <strong>${escapeHtml(device.label)}</strong>
        <small>${escapeHtml(device.advertisedName)} Â· ${device.id} Â· ${device.confidence}</small>
      </div>
      <div class="device-metric">
        <strong>${device.distanceMeters.toFixed(1)} m</strong>
        <small>${device.rssi} dBm</small>
      </div>
    </div>
  `).join('') : '<div class="empty-state">No BLE advertisements detected yet.</div>';
}

const stream = new EventSource('/events');
stream.addEventListener('snapshot', (event) => {
  state = JSON.parse(event.data);
  renderPanel();
});
stream.onerror = () => {
  elements.mode.textContent = 'RECONNECTING';
  elements.status.textContent = 'Lost server connection';
  elements.statusDot.style.background = '#ffb84d';
};

setInterval(() => {
  elements.clock.textContent = new Date().toLocaleTimeString([], { hour12: false });
}, 500);
window.addEventListener('resize', resizeCanvas);
resizeCanvas();
requestAnimationFrame(renderFrame);