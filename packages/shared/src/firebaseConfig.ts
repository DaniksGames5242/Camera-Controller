// firebase.config.json is created locally from firebase.config.example.json and is gitignored.
// It is bundled into your own builds at build time — every device you build talks to the
// same private Firebase project and the same secret "room".
// @ts-ignore - present locally, absent in a fresh checkout until copied from the .example file
import config from './firebase.config.json';

export interface AppFirebaseConfig {
  apiKey: string;
  authDomain: string;
  databaseURL: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
  roomId: string;
}

export const firebaseConfig = config as AppFirebaseConfig;
