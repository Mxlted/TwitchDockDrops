(() => {
  try {
    const savedTheme = window.localStorage.getItem("twitch-dock-drops-theme");
    if (savedTheme === "dark" || savedTheme === "light") {
      document.documentElement.dataset.theme = savedTheme;
    }
  } catch {
    // Local storage can be unavailable in hardened browsers; dark remains the safe default.
  }
})();
