let recognition = null;
let isListening = false;

function initVoice() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    document.getElementById('voice-search-btn').style.display = 'none';
    return;
  }

  recognition = new SpeechRecognition();
  recognition.continuous = false;
  recognition.interimResults = false;
  recognition.lang = 'hi-IN';

  recognition.onresult = (event) => {
    const transcript = event.results[0][0].transcript;
    document.getElementById('search-input').value = transcript;
    isListening = false;
    document.getElementById('voice-search-btn').textContent = '&#127908;';
    performSearch(transcript);
  };

  recognition.onerror = (event) => {
    console.error('Voice error:', event.error);
    isListening = false;
    document.getElementById('voice-search-btn').textContent = '&#127908;';
    if (event.error !== 'no-speech') {
      showToast('Voice recognition failed. Try again.');
    }
  };

  recognition.onend = () => {
    isListening = false;
    document.getElementById('voice-search-btn').textContent = '&#127908;';
  };
}

function toggleVoiceSearch() {
  if (!recognition) {
    showToast('Voice recognition not supported');
    return;
  }

  if (isListening) {
    recognition.stop();
    isListening = false;
    document.getElementById('voice-search-btn').textContent = '&#127908;';
    return;
  }

  if (!isPremium) {
    showPremiumLock('Voice Control');
    return;
  }

  try {
    recognition.start();
    isListening = true;
    document.getElementById('voice-search-btn').textContent = '&#9679;';
    showToast('Listening...');
  } catch (err) {
    showToast('Voice recognition error');
  }
}

function setVoiceLanguage(lang) {
  if (recognition) {
    const langMap = { hindi: 'hi-IN', english: 'en-US', punjabi: 'pa-IN', tamil: 'ta-IN', telugu: 'te-IN' };
    recognition.lang = langMap[lang] || 'hi-IN';
  }
}
