const BLUEZ = 'org.bluez';
const OBJECT_MANAGER = 'org.freedesktop.DBus.ObjectManager';
const PROPERTIES = 'org.freedesktop.DBus.Properties';
const ADAPTER = 'org.bluez.Adapter1';
const DEVICE = 'org.bluez.Device1';

function unwrap(value) {
  if (value && typeof value === 'object' && 'value' in value && 'signature' in value) {
    return unwrap(value.value);
  }
  return value;
}

function entries(value) {
  const raw = unwrap(value);
  if (raw instanceof Map) return [...raw.entries()];
  if (raw && typeof raw === 'object') return Object.entries(raw);
  return [];
}

function property(properties, key) {
  if (properties instanceof Map) return unwrap(properties.get(key));
  return unwrap(properties?.[key]);
}

function manufacturerIds(properties) {
  return entries(property(properties, 'ManufacturerData'))
    .map(([key]) => Number(key))
    .filter(Number.isFinite);
}

function deviceFromProperties(path, properties) {
  const address = property(properties, 'Address');
  const rssi = Number(property(properties, 'RSSI'));
  if (!address || !Number.isFinite(rssi)) return null;

  const txPowerValue = Number(property(properties, 'TxPower'));
  return {
    sourceId: path,
    address,
    addressType: property(properties, 'AddressType') ?? 'unknown',
    name: property(properties, 'Name') ?? '',
    alias: property(properties, 'Alias') ?? '',
    rssi,
    txPower: Number.isFinite(txPowerValue) ? txPowerValue : null,
    manufacturerIds: manufacturerIds(properties)
  };
}

export async function createBluezScanner({ adapterName = 'hci0', onDevice, onStatus }) {
  const module = await import('dbus-next');
  const dbus = module.default ?? module;
  const { Variant } = dbus;
  const bus = dbus.systemBus();
  const root = await bus.getProxyObject(BLUEZ, '/');
  const objectManager = root.getInterface(OBJECT_MANAGER);
  const managed = await objectManager.GetManagedObjects();
  const adapterPath = Object.keys(managed).find((path) =>
    path.endsWith(`/${adapterName}`) && managed[path]?.[ADAPTER]
  );

  if (!adapterPath) {
    bus.disconnect();
    throw new Error(`Bluetooth adapter ${adapterName} was not found by BlueZ.`);
  }

  const subscriptions = new Map();
  let stopped = false;

  async function publish(path, initialProperties) {
    try {
      const proxy = await bus.getProxyObject(BLUEZ, path);
      const propsInterface = proxy.getInterface(PROPERTIES);
      const allProperties = initialProperties ?? await propsInterface.GetAll(DEVICE);
      const parsed = deviceFromProperties(path, allProperties);
      if (parsed) onDevice(parsed);

      if (subscriptions.has(path)) return;
      const listener = (interfaceName, changed) => {
        if (interfaceName !== DEVICE) return;
        propsInterface.GetAll(DEVICE)
          .then((latest) => {
            const update = deviceFromProperties(path, latest);
            if (update) onDevice(update);
          })
          .catch((error) => onStatus?.(`Device update error: ${error.message}`));
      };
      propsInterface.on('PropertiesChanged', listener);
      subscriptions.set(path, { propsInterface, listener });
    } catch (error) {
      onStatus?.(`Could not read ${path}: ${error.message}`);
    }
  }

  for (const [path, interfaces] of Object.entries(managed)) {
    if (interfaces?.[DEVICE]) await publish(path, interfaces[DEVICE]);
  }

  const addedListener = (path, interfaces) => {
    if (interfaces?.[DEVICE]) publish(path, interfaces[DEVICE]);
  };
  const removedListener = (path) => {
    const subscription = subscriptions.get(path);
    if (subscription) {
      subscription.propsInterface.off('PropertiesChanged', subscription.listener);
      subscriptions.delete(path);
    }
  };
  objectManager.on('InterfacesAdded', addedListener);
  objectManager.on('InterfacesRemoved', removedListener);

  const adapterProxy = await bus.getProxyObject(BLUEZ, adapterPath);
  const adapter = adapterProxy.getInterface(ADAPTER);
  await adapter.SetDiscoveryFilter({
    Transport: new Variant('s', 'le'),
    DuplicateData: new Variant('b', true)
  });
  await adapter.StartDiscovery();
  onStatus?.(`Scanning on ${adapterName}`);

  return {
    async stop() {
      if (stopped) return;
      stopped = true;
      objectManager.off('InterfacesAdded', addedListener);
      objectManager.off('InterfacesRemoved', removedListener);
      for (const { propsInterface, listener } of subscriptions.values()) {
        propsInterface.off('PropertiesChanged', listener);
      }
      subscriptions.clear();
      try { await adapter.StopDiscovery(); } catch { /* BlueZ may already be stopping. */ }
      bus.disconnect();
    }
  };
}