const PIPED_API = 'https://pipedapi.kavin.rocks';
let currentTrack = null;
let isPlaying = false;
let audioElement = null;
let wakeLock = null;
let streamUrlCache = {};

async function requestWakeLock() {
  try {
    if ('wakeLock' in navigator) {
      wakeLock = await navigator.wakeLock.request('audio');
      wakeLock.addEventListener('release', () => {});
    }
  } catch (e) {}
}

async function releaseWakeLock() {
  if (wakeLock) {
    try { await wakeLock.release(); } catch(e) {}
    wakeLock = null;
  }
}

async function searchYouTube(query) {
  try {
    const url = `${PIPED_API}/search?q=${encodeURIComponent(query)}&filter=videos`;
    const res = await fetch(url);
    const data = await res.json();
    return (data.items || []).map(item => ({
      id: item.url?.split('v=')[1]?.split('&')[0] || item.url || '',
      title: item.title || 'Unknown',
      artist: item.uploaderName || item.uploader || 'Unknown',
      thumbnail: item.thumbnail || `https://i.ytimg.com/vi/${item.url?.split('v=')[1]?.split('&')[0] || ''}/default.jpg`,
      duration: item.duration || 0,
      uploaderAvatar: item.uploaderAvatar || ''
    }));
  } catch (err) {
    console.error('Search failed:', err);
    return [];
  }
}

async function getAudioStream(videoId) {
  if (streamUrlCache[videoId]) return streamUrlCache[videoId];

  if (window.MediaBridge) {
    return new Promise((resolve) => {
      window.onStreamUrlReady = (url, id) => {
        if (id === videoId && url) {
          streamUrlCache[videoId] = { url, title: '', artist: '', thumbnail: `https://i.ytimg.com/vi/${videoId}/default.jpg` };
          resolve(streamUrlCache[videoId]);
        }
      };
      const syncUrl = window.MediaBridge.getStreamUrl(videoId);
      if (syncUrl) {
        streamUrlCache[videoId] = { url: syncUrl, title: '', artist: '', thumbnail: `https://i.ytimg.com/vi/${videoId}/default.jpg` };
        resolve(streamUrlCache[videoId]);
      } else {
        window.MediaBridge.getStreamUrlAsync(videoId);
        setTimeout(() => {
          if (!streamUrlCache[videoId]) {
            resolve({ url: '', title: '', artist: '', thumbnail: `https://i.ytimg.com/vi/${videoId}/default.jpg` });
          }
        }, 8000);
      }
    });
  }

  try {
    const url = `${PIPED_API}/streams/${videoId}`;
    const res = await fetch(url);
    const data = await res.json();
    const audioStream = data.audioStreams?.find(s => s.mimeType?.startsWith('audio/webm')) ||
                        data.audioStreams?.find(s => s.mimeType?.startsWith('audio/mp4')) ||
                        data.audioStreams?.[0];
    const result = {
      url: audioStream?.url || data.videoStreams?.[0]?.url || '',
      title: data.title || '',
      artist: data.uploader || '',
      thumbnail: data.thumbnailUrl || `https://i.ytimg.com/vi/${videoId}/default.jpg`
    };
    streamUrlCache[videoId] = result;
    return result;
  } catch (err) {
    console.error('Stream fetch failed:', err);
    return null;
  }
}

async function playTrack(track, startIndex) {
  if (!track || !track.id) return;

  if (!await checkPremium()) {
    showPremiumLock('Background Playback');
    return;
  }

  currentTrack = { ...track };
  isPlaying = true;

  try {
    const stream = await getAudioStream(track.id);
    if (!stream || !stream.url) {
      showToast('Could not load audio stream');
      isPlaying = false;
      return;
    }

    if (!audioElement) {
      audioElement = new Audio();
      audioElement.preload = 'auto';
      audioElement.onended = () => handleTrackEnd();
      audioElement.onerror = () => {
        showToast('Playback error. Trying next track...');
        handleTrackEnd();
      };
    }

    audioElement.src = stream.url;
    await audioElement.play();
    await requestWakeLock();

    await FirebasePlugin.incrementDailyPlay();

    // Start native foreground service for background playback
    if (window.MediaBridge) {
      window.MediaBridge.startBgService(track.id);
    }

    notifyNativePlayer({
      action: 'play',
      title: stream.title || track.title,
      artist: stream.artist || track.artist,
      thumbnail: track.thumbnail || stream.thumbnail,
      trackId: track.id
    });

    updateMiniPlayer(track, stream);
    addToRecent(track);

  } catch (err) {
    console.error('Playback failed:', err);
    showToast('Playback failed');
    isPlaying = false;
    if (startIndex !== undefined) {
      playNext();
    }
  }
}

function togglePlayPause() {
  if (!audioElement || !currentTrack) return;
  if (audioElement.paused) {
    audioElement.play();
    isPlaying = true;
    notifyNativePlayer({ action: 'resume', title: currentTrack.title, artist: currentTrack.artist, trackId: currentTrack.id });
  } else {
    audioElement.pause();
    isPlaying = false;
    notifyNativePlayer({ action: 'pause', title: currentTrack.title, artist: currentTrack.artist, trackId: currentTrack.id });
  }
  updatePlayButton();
}

function seekTo(time) {
  if (audioElement) {
    audioElement.currentTime = time;
  }
}

function handleTrackEnd() {
  playNext();
}

function updateMiniPlayer(track, stream) {
  const mini = document.getElementById('mini-player');
  mini.classList.remove('hidden');
  document.getElementById('mini-track-title').textContent = track.title;
  document.getElementById('mini-track-artist').textContent = track.artist;
  const thumb = document.getElementById('mini-thumb');
  thumb.innerHTML = track.thumbnail
    ? `<img src="${track.thumbnail}" alt="thumb" />`
    : '';
  updatePlayButton();
  updateFavButton();
}

function updatePlayButton() {
  const btn = document.getElementById('mini-play-pause');
  btn.textContent = isPlaying ? '&#9646;&#9646;' : '&#9654;';
}

function notifyNativePlayer(data) {
  try {
    if (window.MediaBridge && data.trackId) {
      if (data.action === 'play' || data.action === 'resume') {
        window.MediaBridge.startBgService(data.trackId);
      }
    }
    if (window.MoodFlowBridge) {
      window.MoodFlowBridge.onPlayerStateChange(JSON.stringify(data));
    }
    if (window.MediaControls) {
      window.MediaControls.updateMedia({
        title: data.title || currentTrack?.title || 'MoodFlow',
        artist: data.artist || currentTrack?.artist || '',
        playing: data.action !== 'pause'
      });
    }
  } catch(e) {}
}

// Native control callbacks - called from Android BroadcastReceiver / MediaSession
function mediaOnPlay() { togglePlayPause(); }
function mediaOnPause() { togglePlayPause(); }
function mediaOnNext() { playNext(); }
function mediaOnPrev() { playPrevious(); }
function onNativeAudioStart() { isPlaying = true; updatePlayButton(); }
function sk() { handleTrackEnd(); }

function addToRecent(track) {
  let recent = JSON.parse(localStorage.getItem('recentTracks') || '[]');
  recent = recent.filter(t => t.id !== track.id);
  recent.unshift(track);
  if (recent.length > 20) recent = recent.slice(0, 20);
  localStorage.setItem('recentTracks', JSON.stringify(recent));
  renderRecentTracks();
}

function renderRecentTracks() {
  const container = document.getElementById('recent-tracks');
  const recent = JSON.parse(localStorage.getItem('recentTracks') || '[]');
  if (!recent.length) {
    container.innerHTML = '<p class="text-muted">No recent tracks</p>';
    return;
  }
  container.innerHTML = recent.map((track, i) => createTrackItem(track, i)).join('');
}

function createTrackItem(track, index) {
  const isFav = favorites.some(f => f.id === track.id);
  return `
    <div class="track-item" data-index="${index}" onclick="onTrackClick('${track.id}', ${index})">
      <div class="track-thumb">
        ${track.thumbnail ? `<img src="${track.thumbnail}" alt="" />` : ''}
      </div>
      <div class="track-info">
        <div class="track-title">${track.title}</div>
        <div class="track-artist">${track.artist}</div>
      </div>
      <div class="track-actions">
        <button class="icon-btn heart-btn ${isFav ? 'favorited' : ''}"
          onclick="event.stopPropagation(); toggleFavorite('${track.id}', '${track.title.replace(/'/g, "\\'")}', '${track.artist.replace(/'/g, "\\'")}', '${track.thumbnail}')">
          ${isFav ? '&#9829;' : '&#9825;'}
        </button>
      </div>
    </div>
  `;
}
