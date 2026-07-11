let queue = [];
let currentQueueIndex = 0;
let favorites = [];

const MOOD_QUERIES = {
  happy: 'happy bollywood songs',
  sad: 'sad hindi songs',
  energetic: 'workout bollywood songs',
  calm: 'calm hindi instrumental',
  romantic: 'romantic hindi songs',
  focus: 'focus instrumental music'
};

async function generateQueue(mood, language) {
  const query = MOOD_QUERIES[mood] || 'popular songs';
  const fullQuery = language !== 'all' ? `${query} ${language}` : query;
  showToast(`Generating ${mood} playlist...`);

  const results = await searchYouTube(fullQuery);
  if (results.length === 0) {
    showToast('No results found');
    return;
  }

  queue = results.slice(0, 20);
  currentQueueIndex = 0;
  renderQueue();

  if (queue.length > 0) {
    await playTrack(queue[0], 0);
  }

  switchScreen('queue');
}

function onTrackClick(trackId, index) {
  const allTracks = getVisibleTracks();
  const track = allTracks[index];
  if (!track) return;

  const queueIndex = queue.findIndex(t => t.id === track.id);
  if (queueIndex >= 0) {
    currentQueueIndex = queueIndex;
  } else {
    queue.unshift(track);
    currentQueueIndex = 0;
  }

  playTrack(track, currentQueueIndex);
  renderQueue();
  switchScreen('queue');
}

function getVisibleTracks() {
  const active = document.querySelector('.screen-section.active');
  if (!active) return queue;

  if (active.id === 'screen-search') {
    return window._searchResults || [];
  }
  if (active.id === 'screen-favorites') {
    return favorites;
  }
  return queue;
}

async function playNext() {
  if (queue.length === 0) return;
  currentQueueIndex = (currentQueueIndex + 1) % queue.length;
  const track = queue[currentQueueIndex];
  if (track) await playTrack(track, currentQueueIndex);
  renderQueue();
}

async function playPrevious() {
  if (queue.length === 0) return;
  currentQueueIndex = (currentQueueIndex - 1 + queue.length) % queue.length;
  const track = queue[currentQueueIndex];
  if (track) await playTrack(track, currentQueueIndex);
  renderQueue();
}

function clearQueue() {
  queue = [];
  currentQueueIndex = 0;
  renderQueue();
  showToast('Queue cleared');
}

function renderQueue() {
  const container = document.getElementById('queue-list');
  if (!queue.length) {
    container.innerHTML = '<p class="text-muted">Queue is empty. Search and add songs.</p>';
    document.getElementById('now-playing-info').innerHTML = '<p class="text-muted">No track playing</p>';
    return;
  }

  container.innerHTML = queue.map((track, i) => {
    const isActive = i === currentQueueIndex && currentTrack?.id === track.id;
    return `
      <div class="track-item ${isActive ? 'active' : ''}"
        style="${isActive ? 'border-left: 3px solid var(--accent);' : ''}"
        onclick="playQueueIndex(${i})">
        <div class="track-thumb">
          ${track.thumbnail ? `<img src="${track.thumbnail}" alt="" />` : ''}
        </div>
        <div class="track-info">
          <div class="track-title">${isActive ? '&#9654; ' : ''}${track.title}</div>
          <div class="track-artist">${track.artist}</div>
        </div>
        <div class="track-actions">
          <button class="icon-btn" onclick="event.stopPropagation(); removeFromQueue(${i})">&times;</button>
        </div>
      </div>
    `;
  }).join('');

  const nowPlaying = document.getElementById('now-playing-info');
  if (currentTrack) {
    nowPlaying.innerHTML = `
      <p class="track-title">Now Playing: ${currentTrack.title}</p>
      <p class="track-artist text-muted">${currentTrack.artist}</p>
    `;
  }
}

function playQueueIndex(index) {
  if (index >= 0 && index < queue.length) {
    currentQueueIndex = index;
    playTrack(queue[index], index);
    renderQueue();
  }
}

function removeFromQueue(index) {
  queue.splice(index, 1);
  if (index < currentQueueIndex) currentQueueIndex--;
  else if (index === currentQueueIndex && queue.length > 0) {
    currentQueueIndex = Math.min(currentQueueIndex, queue.length - 1);
    playTrack(queue[currentQueueIndex], currentQueueIndex);
  }
  renderQueue();
}

async function toggleFavorite(id, title, artist, thumbnail) {
  const isFav = favorites.some(f => f.id === id);
  const song = { id, title, artist, thumbnail };

  try {
    if (isFav) {
      favorites = favorites.filter(f => f.id !== id);
      if (currentUser) await FirebasePlugin.removeFavorite(id);
      showToast('Removed from favorites');
    } else {
      favorites.unshift(song);
      if (currentUser) await FirebasePlugin.saveFavorite(song);
      showToast('Added to favorites');
    }
    renderFavorites();
    renderRecentTracks();
    updateFavButton();
  } catch (err) {
    showToast('Error updating favorites');
  }
}

function updateFavButton() {
  const btn = document.getElementById('mini-fav');
  if (!currentTrack) return;
  const isFav = favorites.some(f => f.id === currentTrack.id);
  btn.textContent = isFav ? '&#9829;' : '&#9825;';
  btn.classList.toggle('favorited', isFav);
}

async function loadFavorites() {
  if (!currentUser) {
    favorites = JSON.parse(localStorage.getItem('favorites') || '[]');
  } else {
    favorites = await FirebasePlugin.getFavorites();
  }
  renderFavorites();
}

function renderFavorites() {
  const container = document.getElementById('favorites-list');
  document.getElementById('fav-count').textContent = `${favorites.length} songs`;
  if (!favorites.length) {
    container.innerHTML = '<p class="text-muted">No favorites yet. Tap the heart to add songs.</p>';
    return;
  }
  container.innerHTML = favorites.map((track, i) => createTrackItem(track, i)).join('');
}

function switchScreen(screen) {
  document.querySelectorAll('.screen-section').forEach(s => s.classList.remove('active'));
  document.getElementById(`screen-${screen}`).classList.add('active');
  document.querySelectorAll('.sidebar-menu li').forEach(li => {
    li.classList.toggle('active', li.dataset.screen === screen);
  });
  closeSidebar();
}
