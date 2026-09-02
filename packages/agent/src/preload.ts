import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('mcc', {
  getDeviceInfo: () => ipcRenderer.invoke('get-device-info'),
  quitApp: () => ipcRenderer.send('quit-app'),
  log: (msg: string) => ipcRenderer.send('agent-log', msg),
});
