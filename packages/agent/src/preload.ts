import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('mcc', {
  getDeviceInfo: () => ipcRenderer.invoke('get-device-info'),
  quitApp: () => ipcRenderer.send('quit-app'),
});
