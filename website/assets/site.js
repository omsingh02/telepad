// Telepad landing page. Small on purpose: no libraries, no network requests.
(() => {
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Fade sections in as they scroll into view.
  const revealed = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !reduceMotion) {
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('in');
          observer.unobserve(entry.target);
        }
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    revealed.forEach((el) => observer.observe(el));
  } else {
    revealed.forEach((el) => el.classList.add('in'));
  }

  // Previous and next buttons for the screenshot strip (it also scrolls and swipes by itself).
  const track = document.getElementById('gallery-track');
  document.querySelectorAll('[data-gallery]').forEach((button) => {
    button.addEventListener('click', () => {
      const direction = button.dataset.gallery === 'next' ? 1 : -1;
      track.scrollBy({ left: direction * Math.max(260, track.clientWidth * 0.6), behavior: reduceMotion ? 'auto' : 'smooth' });
    });
  });

  // Point visitors at the download that fits the device they are on.
  const hints = `${(navigator.userAgentData && navigator.userAgentData.platform) || ''} ${navigator.platform || ''}`;
  const agent = navigator.userAgent || '';
  let system = '';
  if (/android/i.test(agent)) system = 'android';               // Android's agent also says Linux
  else if (/iphone|ipad|ipod/i.test(agent)) system = '';        // nothing to offer on iOS
  else if (/win/i.test(hints) || /windows/i.test(agent)) system = 'windows';
  else if (/mac/i.test(hints) || /macintosh/i.test(agent)) system = 'macos';
  else if (/linux|x11|cros/i.test(hints + agent)) system = 'linux';
  const card = system && document.querySelector(`.dl[data-os="${system}"]`);
  if (card) card.classList.add('is-recommended');
})();
