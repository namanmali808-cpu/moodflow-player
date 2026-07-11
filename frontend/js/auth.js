let currentUser = null;
let isPremium = false;

async function initAuth() {
  FirebasePlugin.onAuthStateChanged(async (user) => {
    currentUser = user;
    if (user) {
      isPremium = await FirebasePlugin.getPremiumStatus();
      hideScreen('auth-screen');
      showScreen('main-screen');
      updateUserUI(user);
      await loadFavorites();
    }
  });
}

function updateUserUI(user) {
  const name = user?.displayName || user?.email?.split('@')[0] || 'User';
  document.getElementById('sidebar-username').textContent = name;
  document.getElementById('sidebar-email').textContent = user?.email || '';
  document.getElementById('user-avatar').textContent = name.charAt(0).toUpperCase();
  document.getElementById('premium-badge').classList.toggle('hidden', !isPremium);
}

async function handleAuth(e) {
  e.preventDefault();
  const email = document.getElementById('auth-email').value.trim();
  const password = document.getElementById('auth-password').value;
  const isSignUp = document.getElementById('auth-submit-btn').textContent === 'Sign Up';
  const errorEl = document.getElementById('auth-error');
  errorEl.classList.add('hidden');

  try {
    if (isSignUp) {
      if (password.length < 6) throw new Error('Password must be at least 6 characters');
      await FirebasePlugin.signUp(email, password);
    } else {
      await FirebasePlugin.signIn(email, password);
    }
  } catch (err) {
    errorEl.textContent = err.message;
    errorEl.classList.remove('hidden');
  }
}

function toggleAuthMode() {
  const btn = document.getElementById('auth-submit-btn');
  const toggle = document.getElementById('auth-toggle-text');
  const link = document.getElementById('auth-toggle-link');
  const isSignUp = btn.textContent === 'Sign Up';
  btn.textContent = isSignUp ? 'Sign In' : 'Sign Up';
  toggle.textContent = isSignUp ? 'Already have an account?' : "Don't have an account?";
  link.textContent = isSignUp ? 'Sign In' : 'Sign Up';
  document.getElementById('auth-error').classList.add('hidden');
}

async function handleLogout() {
  await FirebasePlugin.signOut();
  currentUser = null;
  isPremium = false;
  showScreen('auth-screen');
  hideScreen('main-screen');
  queue = [];
  favorites = [];
}

async function checkPremium() {
  if (currentUser) {
    isPremium = await FirebasePlugin.getPremiumStatus();
    document.getElementById('premium-badge').classList.toggle('hidden', !isPremium);
  }
  return isPremium;
}

function showPremiumLock(featureName) {
  document.getElementById('premium-feature-name').textContent =
    `${featureName} requires a premium subscription.`;
  showScreen('premium-lock');
}

async function handleSubscribe() {
  showToast('Redirecting to payment...');
  if (!currentUser) return;
  try {
    const amount = 41;
    const orderRes = await fetch('YOUR_PAYMENT_BACKEND/create-order', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uid: currentUser.uid, amount })
    });
    const order = await orderRes.json();
    showToast('Payment initiated. In production, integrate Razorpay/PhonePe here.');
    // In production: integrate Razorpay or PhonePe SDK here
    // On success callback:
    await FirebasePlugin.setPremiumStatus(currentUser.uid, true);
    isPremium = true;
    document.getElementById('premium-badge').classList.remove('hidden');
    hideScreen('premium-lock');
    showScreen('main-screen');
    showToast('Welcome to Premium!');
  } catch (err) {
    showToast('Payment failed: ' + err.message);
  }
}
