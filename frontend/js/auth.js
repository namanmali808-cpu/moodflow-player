let currentUser = null;
let isPremium = false;

function initAuth() {
  FirebasePlugin.onAuthStateChanged(async (user) => {
    currentUser = user;
    if (user) {
      isPremium = await FirebasePlugin.getPremiumStatus().catch(() => false);
      hideScreen('auth-screen');
      showScreen('main-screen');
      updateUserUI(user);
      loadFavorites();
    }
  });
}

function updateUserUI(user) {
  const name = user?.displayName || user?.email?.split('@')[0] || 'User';
  $('sidebar-username').textContent = name;
  $('sidebar-email').textContent = user?.email || '';
  $('user-avatar').textContent = name.charAt(0).toUpperCase();
  $('premium-badge')?.classList.toggle('hidden', !isPremium);
}

async function handleAuth(e) {
  e.preventDefault();
  const email = $('auth-email')?.value?.trim();
  const password = $('auth-password')?.value;
  const isSignUp = $('auth-submit-btn')?.textContent === 'Sign Up';
  const errorEl = $('auth-error');
  errorEl?.classList.add('hidden');

  if (!email || !password) {
    if (errorEl) { errorEl.textContent = 'Email and password required'; errorEl.classList.remove('hidden'); }
    return;
  }

  try {
    if (isSignUp) {
      if (password.length < 6) throw new Error('Password must be at least 6 characters');
      await FirebasePlugin.signUp(email, password);
    } else {
      await FirebasePlugin.signIn(email, password);
    }
  } catch (err) {
    if (errorEl) { errorEl.textContent = err.message; errorEl.classList.remove('hidden'); }
  }
}

function toggleAuthMode() {
  const btn = $('auth-submit-btn');
  const toggle = $('auth-toggle-text');
  const link = $('auth-toggle-link');
  if (!btn) return;
  const isSignUp = btn.textContent === 'Sign Up';
  btn.textContent = isSignUp ? 'Sign In' : 'Sign Up';
  if (toggle) toggle.textContent = isSignUp ? 'Already have an account?' : "Don't have an account?";
  if (link) link.textContent = isSignUp ? 'Sign In' : 'Sign Up';
  $('auth-error')?.classList.add('hidden');
}

async function handleLogout() {
  await FirebasePlugin.signOut();
  currentUser = null;
  isPremium = false;
  queue = [];
  favorites = [];
  showScreen('auth-screen');
  hideScreen('main-screen');
}

async function checkPremium() {
  if (currentUser) isPremium = await FirebasePlugin.getPremiumStatus().catch(() => false);
  return isPremium;
}

function showPremiumLock(featureName) {
  $('premium-feature-name').textContent = featureName + ' requires a premium subscription.';
  showScreen('premium-lock');
}

async function handleSubscribe() {
  showToast('Payment integration coming soon...');
  // Razorpay/PhonePe integration - requires merchant account
}
