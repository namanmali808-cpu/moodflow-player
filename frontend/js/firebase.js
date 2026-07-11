const firebaseConfig = {
  apiKey: "AIzaSyAFwUYmVsKE_SSxRBoGxsTFFXclJjSAfl0",
  authDomain: "yt-mood-player-naman.firebaseapp.com",
  projectId: "yt-mood-player-naman",
  storageBucket: "yt-mood-player-naman.firebasestorage.app",
  messagingSenderId: "79051163289",
  appId: "1:79051163289:web:b6bd4ba9508b495678da08"
};

firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();

const FirebasePlugin = {
  async signIn(email, password) {
    const cred = await auth.signInWithEmailAndPassword(email, password);
    return { user: cred.user.toJSON() };
  },
  async signUp(email, password) {
    const cred = await auth.createUserWithEmailAndPassword(email, password);
    await db.collection('users').doc(cred.user.uid).set({
      email: cred.user.email,
      createdAt: firebase.firestore.FieldValue.serverTimestamp(),
      isPremium: false,
      dailyPlays: 0,
      lastPlayDate: null
    });
    return { user: cred.user.toJSON() };
  },
  async signOut() {
    await auth.signOut();
  },
  getCurrentUser() {
    return auth.currentUser;
  },
  onAuthStateChanged(callback) {
    return auth.onAuthStateChanged(callback);
  },
  async saveFavorite(song) {
    const user = auth.currentUser;
    if (!user) return;
    const ref = db.collection('users').doc(user.uid).collection('favorites').doc(song.id);
    await ref.set({
      ...song,
      addedAt: firebase.firestore.FieldValue.serverTimestamp()
    });
  },
  async removeFavorite(songId) {
    const user = auth.currentUser;
    if (!user) return;
    await db.collection('users').doc(user.uid).collection('favorites').doc(songId).delete();
  },
  async getFavorites() {
    const user = auth.currentUser;
    if (!user) return [];
    const snap = await db.collection('users').doc(user.uid).collection('favorites').orderBy('addedAt', 'desc').get();
    return snap.docs.map(d => ({ id: d.id, ...d.data() }));
  },
  async isFavorite(songId) {
    const user = auth.currentUser;
    if (!user) return false;
    const doc = await db.collection('users').doc(user.uid).collection('favorites').doc(songId).get();
    return doc.exists;
  },
  async incrementDailyPlay() {
    const user = auth.currentUser;
    if (!user) return;
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
    });
  },
  async getPremiumStatus() {
    const user = auth.currentUser;
    if (!user) return false;
    const doc = await db.collection('users').doc(user.uid).get();
    return doc.data()?.isPremium || false;
  },
  async setPremiumStatus(uid, status) {
    await db.collection('users').doc(uid).update({ isPremium: status });
  }
};
