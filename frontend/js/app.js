function $(id) { return document.getElementById(id); }

function showScreen(id) { $(id).classList.remove('hidden'); }
function hideScreen(id) { $(id).classList.add('hidden'); }

function showToast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(el._hide);
  el._hide = setTimeout(() => el.classList.add('hidden'), 3000);
}

function openSidebar() { $('sidebar').classList.add('open'); }
function closeSidebar() { $('sidebar').classList.remove('open'); }

document.addEventListener('DOMContentLoaded', async () => {
  // Splash
  setTimeout(() => {
    hideScreen('splash');
    if (!currentUser) {
      showScreen('auth-screen');
    }
  }, 1500);

  initAuth();
  initVoice();

  // Auth form
  $('auth-form').addEventListener('submit', handleAuth);
  $('auth-toggle-link').addEventListener('click', (e) => { e.preventDefault(); toggleAuthMode(); });
  $('skip-auth-btn').addEventListener('click', () => {
    hideScreen('auth-screen');
    showScreen('main-screen');
  });

  // Sidebar
  $('menu-btn').addEventListener('click', openSidebar);
  $('sidebar-close').addEventListener('click', closeSidebar);
  document.querySelectorAll('.sidebar-menu li').forEach(li => {
    li.addEventListener('click', () => switchScreen(li.dataset.screen));
  });
  document.addEventListener('click', (e) => {
    if (e.target.closest('.sidebar')) return;
    if (e.target.closest('#menu-btn')) return;
    closeSidebar();
  });

  // Search
  $('search-btn').addEventListener('click', () => performSearch($('search-input').value));
  $('search-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') performSearch($('search-input').value);
  });
  $('voice-search-btn').addEventListener('click', toggleVoiceSearch);

  // Mini player controls
  $('mini-play-pause').addEventListener('click', togglePlayPause);
  $('mini-next').addEventListener('click', playNext);
  $('mini-prev').addEventListener('click', playPrevious);
  $('mini-fav').addEventListener('click', () => {
    if (currentTrack) {
      toggleFavorite(currentTrack.id, currentTrack.title, currentTrack.artist, currentTrack.thumbnail);
    }
  });
  $('mini-player').addEventListener('click', (e) => {
    if (e.target.closest('.mini-controls') || e.target.closest('.mini-thumb')) return;
    switchScreen('queue');
  });

  // Mood grid
  document.querySelectorAll('.mood-card').forEach(card => {
    card.addEventListener('click', () => {
      const mood = card.dataset.mood;
      const lang = document.querySelector('.lang-chip.active')?.dataset?.lang || 'all';
      generateQueue(mood, lang);
    });
  });

  // Language chips
  document.querySelectorAll('.lang-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.lang-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      if (recognition) setVoiceLanguage(chip.dataset.lang);
    });
  });

  // Queue
  $('clear-queue-btn').addEventListener('click', clearQueue);

  // Premium
  $('subscribe-btn').addEventListener('click', handleSubscribe);
  $('premium-back-btn').addEventListener('click', () => {
    hideScreen('premium-lock');
    showScreen('main-screen');
  });

  // Settings / Logout
  $('settings-btn').addEventListener('click', openSidebar);
  $('logout-btn').addEventListener('click', handleLogout);

  // Auto-update check
  checkForUpdates();

  // Greeting
  updateGreeting();
});

function updateGreeting() {
  const h = new Date().getHours();
  let greeting = 'Good evening';
  if (h < 12) greeting = 'Good morning';
  else if (h < 17) greeting = 'Good afternoon';
  $('welcome-text').textContent = greeting;
}

async function performSearch(query) {
  if (!query.trim()) return;
  switchScreen('search');
  const container = $('search-results');
  container.innerHTML = '<div class="loader" style="margin:2rem auto;"></div>';

  const results = await searchYouTube(query.trim());
  window._searchResults = results;

  if (!results.length) {
    container.innerHTML = '<p class="text-muted">No results found. Try a different search.</p>';
    return;
  }

  container.innerHTML = results.map((track, i) => createTrackItem(track, i)).join('');
}

async function checkForUpdates() {
  try {
    const res = await fetch('https://raw.githubusercontent.com/namanmali808-cpu/moodflow-player/main/version.json');
    const data = await res.json();
    const currentVer = '1.2.0';
    if (data.version !== currentVer) {
      showToast(`Update available: ${data.version}`);
      setTimeout(() => {
        if (confirm(`Update ${data.version} available. Download?`)) {
          try {
            if (window.MediaBridge) {
              window.MediaBridge.downloadApk(data.apkUrl);
            } else if (window.MoodFlowBridge) {
              window.MoodFlowBridge.downloadUpdate(data.apkUrl);
            } else {
              window.open(data.apkUrl, '_system');
            }
          } catch(e) {
            showToast('Open update page to download');
          }
        }
      }, 3000);
    }
  } catch (err) {
    console.log('Update check skipped (not in production)');
  }
}

window.addEventListener('online', () => {});
window.addEventListener('offline', () => showToast('No internet connection'));

// Expose for native bridge
window.MoodFlowActions = {
  playTrack,
  togglePlayPause,
  playNext,
  playPrevious,
  seekTo,
  getCurrentState: () => ({
    track: currentTrack,
    isPlaying,
    queue,
    queueIndex: currentQueueIndex
  })
};
