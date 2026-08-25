const COMPANY_IDS = Object.freeze({
  APPLE: 0x004c,
  SAMSUNG: 0x0075,
  GOOGLE: 0x00e0,
  GOOGLE_LLC: 0x018e,
  RASPBERRY_PI: 0x1040
});

const NAME_RULES = [
  { category: 'raspberry_pi', pattern: /\b(raspberry\s*pi|raspberrypi|rpi)\b/i },
  { category: 'samsung', pattern: /\b(samsung|galaxy|sm-[a-z0-9]+)\b/i },
  { category: 'apple', pattern: /\b(iphone|ipad|apple\s*watch|macbook|airpods?)\b/i },
  {
    category: 'android',
    pattern: /\b(android|pixel|oneplus|oppo|xiaomi|redmi|realme|huawei|honor|motorola|moto\s|nothing\s*phone|fairphone|sony\s*xperia)\b/i
  }
];

function normalizeIds(ids = []) {
  return ids
    .map((id) => typeof id === 'string' ? Number.parseInt(id, 0) : Number(id))
    .filter(Number.isFinite);
}

export function classifyDevice({ manufacturerIds = [], name = '', alias = '' } = {}) {
  const ids = new Set(normalizeIds(manufacturerIds));

  if (ids.has(COMPANY_IDS.APPLE)) return { category: 'apple', confidence: 'high' };
  if (ids.has(COMPANY_IDS.RASPBERRY_PI)) return { category: 'raspberry_pi', confidence: 'high' };
  if (ids.has(COMPANY_IDS.SAMSUNG)) return { category: 'samsung', confidence: 'high' };
  if (ids.has(COMPANY_IDS.GOOGLE) || ids.has(COMPANY_IDS.GOOGLE_LLC)) {
    return { category: 'android', confidence: 'medium' };
  }

  const searchableName = `${name} ${alias}`.trim();
  for (const rule of NAME_RULES) {
    if (rule.pattern.test(searchableName)) {
      return { category: rule.category, confidence: 'medium' };
    }
  }

  return { category: 'other', confidence: 'low' };
}

export { COMPANY_IDS };