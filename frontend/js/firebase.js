let auth = null;
let db = null;
let firebaseReady = false;

try {
  const firebaseConfig = {
    apiKey: "AIzaSyAFwUYmVsKE_SSxRBoGxsTFFXclJjSAfl0",
    authDomain: "yt-mood-player-naman.firebaseapp.com",
    projectId: "yt-mood-player-naman",
    storageBucket: "yt-mood-player-naman.firebasestorage.app",
    messagingSenderId: "79051163289",
    appId: "1:79051163289:web:b6bd4ba9508b495678da08"
  };
  if (typeof firebase !== 'undefined') {
    firebase.initializeApp(firebaseConfig);
    auth = firebase.auth();
    db = firebase.firestore();
    firebaseReady = true;
  }
} catch (e) {
  console.warn('Firebase init failed:', e);
}

const FirebasePlugin = {
  async signIn(email, password) {
    if (!auth) throw new Error('Firebase not initialized');
    const cred = await auth.signInWithEmailAndPassword(email, password);
    return { user: cred.user.toJSON() };
  },
  async signUp(email, password) {
    if (!auth) throw new Error('Firebase not initialized');
    const cred = await auth.createUserWithEmailAndPassword(email, password);
    if (db) {
      await db.collection('users').doc(cred.user.uid).set({
        email: cred.user.email,
        createdAt: firebase.firestore.FieldValue.serverTimestamp(),
        isPremium: false,
        dailyPlays: 0,
        lastPlayDate: null
      }).catch(() => {});
    }
    return { user: cred.user.toJSON() };
  },
  async signOut() {
    if (auth) await auth.signOut();
  },
  getCurrentUser() {
    return auth ? auth.currentUser : null;
  },
  onAuthStateChanged(callback) {
    if (!auth) { setTimeout(() => callback(null), 100); return function(){}; }
    return auth.onAuthStateChanged(callback);
  },
  async saveFavorite(song) {
    const user = auth?.currentUser;
    if (!user || !db) return;
    await db.collection('users').doc(user.uid).collection('favorites').doc(song.id).set({
      ...song, addedAt: firebase.firestore.FieldValue.serverTimestamp()
    }).catch(() => {});
  },
  async removeFavorite(songId) {
    const user = auth?.currentUser;
    if (!user || !db) return;
    await db.collection('users').doc(user.uid).collection('favorites').doc(songId).delete().catch(() => {});
  },
  async getFavorites() {
    const user = auth?.currentUser;
    if (!user || !db) return [];
    const snap = await db.collection('users').doc(user.uid).collection('favorites').orderBy('addedAt', 'desc').get().catch(() => null);
    return snap ? snap.docs.map(d => ({ id: d.id, ...d.data() })) : [];
  },
  async isFavorite(songId) {
    const user = auth?.currentUser;
    if (!user || !db) return false;
    const doc = await db.collection('users').doc(user.uid).collection('favorites').doc(songId).get().catch(() => null);
    return doc ? doc.exists : false;
  },
  async incrementDailyPlay() {
    const user = auth?.currentUser;
    if (!user || !db) return;
    const ref = db.collection('users').doc(user.uid);
    const today = new Date().toISOString().split('T')[0];
    await db.runTransaction(async (t) => {
      const doc = await t.get(ref);
      const data = doc.data() || {};
      if (data.lastPlayDate !== today) {
        t.update(ref, { dailyPlays: 1, lastPlayDate: today });
      } else {
        t.update(ref, { dailyPlays: firebase.firestore.FieldValue.increment(1) });
      }
    }).catch(() => {});
  },
  async getPremiumStatus() {
    const user = auth?.currentUser;
    if (!user || !db) return false;
    const doc = await db.collection('users').doc(user.uid).get().catch(() => null);
    return doc?.data()?.isPremium || false;
  },
  async setPremiumStatus(uid, status) {
    if (!db) return;
    await db.collection('users').doc(uid).update({ isPremium: status }).catch(() => {});
  }
};
