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
  var finished = false;

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
