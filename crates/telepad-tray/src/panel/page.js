// Keeps the page honest: counts down the code, and shows when a phone has paired or the code ran out.
(function () {
  'use strict';
  var state = document.getElementById('state');
  var startPaired = Number(state.dataset.paired);
  var seconds = Number(state.dataset.seconds);
  var countdown = document.getElementById('countdown');
  var card = document.getElementById('code');
  var done = document.getElementById('done');
  var expired = document.getElementById('expired');
  var steps = document.getElementById('steps');
  var statusLine = document.getElementById('status');
  var allow = document.getElementById('allow');
  var allowButton = document.getElementById('allow-button');
  var allowNote = document.getElementById('allow-note');
  var allowTerminal = document.getElementById('allow-terminal');
  var allowed = document.getElementById('allowed');
  var wasBlocked = state.dataset.blocked === '1';
  var finished = false;

  var updateBox = document.getElementById('update');
  var updateTitle = document.getElementById('update-title');
  var updateText = document.getElementById('update-text');
  var updateProgress = document.getElementById('update-progress');
  var updateButton = document.getElementById('update-button');
  var updateLink = document.getElementById('update-link');
  var updateHow = document.getElementById('update-how');
  var updateLine = document.getElementById('update-line');
  var checkButton = document.getElementById('check-updates');
  var autoButton = document.getElementById('auto-updates');
  var autoIsOn = true;

  function post(path) {
    return fetch(path, { method: 'POST' }).catch(function () { /* the program has quit */ });
  }

  updateButton.addEventListener('click', function () { updateButton.disabled = true; post('install-update'); });
  checkButton.addEventListener('click', function () { checkButton.disabled = true; post('check-updates'); });
  autoButton.addEventListener('click', function () { post('auto-updates?on=' + (autoIsOn ? '0' : '1')); });

  // Only an address on this project's GitHub is ever put in a link.
  function projectAddress(url) {
    return typeof url === 'string' && url.indexOf('https://github.com/omsingh02/telepad/') === 0;
  }

  function showUpdate(u) {
    autoIsOn = u.auto;
    var version = u.version;
    var box = true;
    var title = '';
    var text = '';
    var button = null;
    var progress = null;
    var linkText = 'What changed';
    var line = 'Telepad ' + u.current;
    var busy = false;

    switch (u.phase) {
      case 'available':
        title = 'Telepad ' + version + ' is available';
        text = u.can_install
          ? 'You have ' + u.current + '. Installing takes a moment, and Telepad restarts by itself.'
          : (u.by_hand || ('You have ' + u.current + '.'));
        if (u.can_install) button = 'Install and restart';
        else linkText = 'Open the release page';
        line += ' \u00b7 ' + version + ' is available';
        break;
      case 'downloading':
        title = 'Downloading Telepad ' + version + '\u2026';
        text = 'It is checked against the release\u2019s published checksum before anything is installed.';
        progress = u.percent === null ? -1 : u.percent;
        line = 'Downloading ' + version + '\u2026';
        busy = true;
        break;
      case 'installing':
        title = 'Installing Telepad ' + version + '\u2026';
        text = 'Your system may ask for your password.';
        line = 'Installing ' + version + '\u2026';
        busy = true;
        break;
      case 'restarting':
        title = 'Telepad ' + version + ' is installed';
        text = 'Telepad is restarting, and this page stops working. Open Telepad from the tray or the menu if you need it.';
        line = 'Restarting\u2026';
        busy = true;
        break;
      case 'install_failed':
        title = u.declined ? 'The update was not installed' : 'The update did not install';
        text = u.message || '';
        if (u.can_install) button = 'Try again';
        linkText = 'Open the release page';
        line += ' \u00b7 ' + version + ' is available';
        break;
      case 'checking':
        box = false;
        line = 'Checking for updates\u2026';
        busy = true;
        break;
      case 'up_to_date':
        box = false;
        line = 'Telepad ' + u.current + ' is up to date.';
        break;
      case 'check_failed':
        box = false;
        line = 'Could not check for updates: ' + (u.message || 'no answer');
        break;
      default:
        box = false;
    }

    updateBox.hidden = !box;
    updateTitle.textContent = title;
    updateText.textContent = text;
    updateButton.hidden = button === null;
    updateButton.disabled = false;
    if (button !== null) updateButton.textContent = button;
    updateProgress.hidden = progress === null;
    if (progress !== null) {
      if (progress < 0) updateProgress.removeAttribute('value');
      else updateProgress.value = progress;
    }
    updateLink.hidden = !(box && projectAddress(u.page));
    if (!updateLink.hidden) {
      updateLink.href = u.page;
      updateLink.textContent = linkText;
    }
    setText(updateHow, box ? u.how : null);
    updateLine.textContent = line;
    checkButton.disabled = busy;
    autoButton.textContent = 'Looks for updates: ' + (u.auto ? 'on' : 'off');
  }

  function setText(element, text) {
    element.textContent = text || '';
    element.hidden = !text;
  }

  if (allowButton) {
    allowButton.addEventListener('click', function () {
      allowButton.disabled = true;
      fetch('allow', { method: 'POST' }).catch(function () { /* the program has quit */ });
    });
  }

  function clock(total) {
    var m = Math.floor(total / 60);
    var s = total % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  function show() {
    countdown.textContent = clock(Math.max(seconds, 0));
    if (seconds <= 0 && !finished) {
      card.hidden = true;
      steps.hidden = true;
      expired.hidden = false;
    }
  }

  setInterval(function () {
    if (seconds > 0) seconds -= 1;
    show();
  }, 1000);

  function poll() {
    fetch('status.json', { cache: 'no-store' })
      .then(function (response) { return response.json(); })
      .then(function (s) {
        statusLine.textContent = s.connected > 0
          ? s.connected + (s.connected === 1 ? ' phone is connected.' : ' phones are connected.')
          : (s.paired > 0 ? s.paired + (s.paired === 1 ? ' phone is paired.' : ' phones are paired.') : 'No phone is paired yet.');
        if (s.update) showUpdate(s.update);
        if (s.blocked) {
          // It became missing after this page was drawn (a Mac that forgot after an update): draw it again.
          if (!allow) { location.reload(); return; }
          allowButton.disabled = s.working;
          setText(allowNote, s.note);
          setText(allowTerminal, s.terminal);
        } else if (wasBlocked) {
          wasBlocked = false;
          if (allow) allow.hidden = true;
          allowed.hidden = false;
        }
        if (s.paired > startPaired && !finished) {
          finished = true;
          card.hidden = true;
          steps.hidden = true;
          expired.hidden = true;
          done.hidden = false;
        }
      })
      .catch(function () { /* the program has quit: nothing more to show */ });
  }

  show();
  poll();
  setInterval(poll, 1500);
})();
