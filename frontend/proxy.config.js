// Proxy dev: teruskan Admin API ke backend Behavio (:9000).
const PROXY_CONFIG = {
  '/api': {
    target: 'http://localhost:9000',
    changeOrigin: true,
    secure: false,
  },
};

module.exports = PROXY_CONFIG;
