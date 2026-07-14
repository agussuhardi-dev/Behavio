// Proxy dev: teruskan Admin API ke backend Behavio (:8080).
const PROXY_CONFIG = {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
    secure: false,
  },
};

module.exports = PROXY_CONFIG;
