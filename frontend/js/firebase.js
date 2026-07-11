let auth = null;
let db = null;
let firebaseReady = false;
let firebaseLoading = false;
let authCallbacks = [];

function initFirebase() {
  if (firebaseReady || firebaseLoading) return;
  firebaseLoading = true;

  try {
    if (typeof firebase !== 'undefined') {
      setupFirebase();
      return;
    }
  } catch(e) {}

  const scripts = [
    'https://www.gstatic.com/firebasejs/10.12.0/firebase-app-compat.js',
    'https://www.gstatic.com/firebasejs/10.12.0/firebase-auth-compat.js',
    'https://www.gstatic.com/firebasejs/10.12.0/firebase-firestore-compat.js'
  ];

  let loaded = 0;
  scripts.forEach(src => {
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => {
      loaded++;
      if (loaded === scripts.length) {
        try { setupFirebase(); } catch(e) { firebaseLoading = false; }
      }
    };
    s.onerror = () => {
      loaded++;
      if (loaded === scripts.length) {
        firebaseLoading = false;
        notifyAuthState(null);
      }
    };
    document.head.appendChild(s);
  });

  setTimeout(() => {
    if (!firebaseReady) {
      firebaseLoading = false;
      notifyAuthState(null);
    }
  }, 10000);
}

function setupFirebase() {
  try {
    firebase.initializeApp({
      apiKey: "AIzaSyAFwUYmVsKE_SSxRBoGxsTFFXclJjSAfl0",
      authDomain: "yt-mood-player-naman.firebaseapp.com",
      projectId: "yt-mood-player-naman",
      storageBucket: "yt-mood-player-naman.firebasestorage.app",
      messagingSenderId: "79051163289",
      appId: "1:79051163289:web:b6bd4ba9508b495678da08"
    });
    auth = firebase.auth();
    db = firebase.firestore();
    firebaseReady = true;
    firebaseLoading = false;
    auth.onAuthStateChanged(user => notifyAuthState(user));
  } catch(e) {
    firebaseLoading = false;
    notifyAuthState(null);
  }
}

function notifyAuthState(user) {
  currentUser = user;
  authCallbacks.forEach(cb => cb(user));
}

const FirebasePlugin = {
  init() { initFirebase(); },
  async signIn(email, password) {
    if (!auth) throw new Error('Firebase not loaded. Check internet connection.');
    const cred = await auth.signInWithEmailAndPassword(email, password);
    return { user: cred.user.toJSON() };
  },
  async signUp(email, password) {
    if (!auth) throw new Error('Firebase not loaded. Check internet connection.');
    const cred = await auth.createUserWithEmailAndPassword(email, password);
    if (db) {
      await db.collection('users').doc(cred.user.uid).set({
        email: cred.user.email,
        createdAt: firebase.firestore.FieldValue.serverTimestamp(),
        isPremium: false, dailyPlays: 0, lastPlayDate: null
      }).catch(() => {});
    }
    return { user: cred.user.toJSON() };
  },
  async signOut() {
    if (auth) await auth.signOut();
    currentUser = null;
  },
  getCurrentUser() { return auth ? auth.currentUser : null; },
  onAuthStateChanged(callback) {
    authCallbacks.push(callback);
    return () => { authCallbacks = authCallbacks.filter(c => c !== callback); };
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
    const snap = await db.collection('users').doc(user.uid).collection('favorites')
      .orderBy('addedAt', 'desc').get().catch(() => null);
    return snap ? snap.docs.map(d => ({ id: d.id, ...d.data() })) : [];
  },
  async incrementDailyPlay() {
    const user = auth?.currentUser;
    if (!user || !db) return;
    const today = new Date().toISOString().split('T')[0];
    await db.collection('users').doc(user.uid).get().then(async (doc) => {
      const data = doc.data() || {};
      if (data.lastPlayDate !== today) {
        await doc.ref.update({ dailyPlays: 1, lastPlayDate: today }).catch(() => {});
      } else {
        await doc.ref.update({ dailyPlays: firebase.firestore.FieldValue.increment(1) }).catch(() => {});
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
