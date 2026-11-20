/**
 * Copyright 2018 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// If the loader is already loaded, just stop.
if (!self.define) {
  let registry = {};

  // Used for `eval` and `importScripts` where we can't get script URL by other means.
  // In both cases, it's safe to use a global var because those functions are synchronous.
  let nextDefineUri;

  const singleRequire = (uri, parentUri) => {
    uri = new URL(uri + ".js", parentUri).href;
    return registry[uri] || (
      
        new Promise(resolve => {
          if ("document" in self) {
            const script = document.createElement("script");
            script.src = uri;
            script.onload = resolve;
            document.head.appendChild(script);
          } else {
            nextDefineUri = uri;
            importScripts(uri);
            resolve();
          }
        })
      
      .then(() => {
        let promise = registry[uri];
        if (!promise) {
          throw new Error(`Module ${uri} didn’t register its module`);
        }
        return promise;
      })
    );
  };

  self.define = (depsNames, factory) => {
    const uri = nextDefineUri || ("document" in self ? document.currentScript.src : "") || location.href;
    if (registry[uri]) {
      // Module is already loading or loaded.
      return;
    }
    let exports = {};
    const require = depUri => singleRequire(depUri, uri);
    const specialDeps = {
      module: { uri },
      exports,
      require
    };
    registry[uri] = Promise.all(depsNames.map(
      depName => specialDeps[depName] || require(depName)
    )).then(deps => {
      factory(...deps);
      return exports;
    });
  };
}
define(['./workbox-afac4cd2'], (function (workbox) { 'use strict';

  self.skipWaiting();
  workbox.clientsClaim();
  /**
   * The precacheAndRoute() method efficiently caches and responds to
   * requests for URLs in the manifest.
   * See https://goo.gl/S9QRab
   */
  workbox.precacheAndRoute([{
    "url": "pwa-maskable-512x512.png",
    "revision": "dc55673e3f8847d842950e5714b26404"
  }, {
    "url": "pwa-512x512.png",
    "revision": "b6f619f31b8b8b2bcc3254278ed0a513"
  }, {
    "url": "pwa-192x192.png",
    "revision": "a15eccf68f45a1323a0b1b76f0838aa5"
  }, {
    "url": "index.html",
    "revision": "33b899254da642d16e2fe90072fe1cf5"
  }, {
    "url": "icon.svg",
    "revision": "9606b183782283b180f6dd4b730a6073"
  }, {
    "url": "apple-touch-icon.png",
    "revision": "546982f080ce639630d660df874df74f"
  }, {
    "url": "assets/workbox-window.prod.es5-Bd17z0YL.js",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-600-normal-DMks36a2.woff",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-600-normal-BsaQcF38.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-500-normal-CTEe1bJa.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-500-normal-CAxz3nsc.woff",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-400-normal-r32jotim.woff",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-ext-400-normal-Q_nF8v4l.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-600-normal-B9e8oLYv.woff",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-600-normal-B7fBEWYG.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-500-normal-Z6ESRlEs.woff",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-500-normal-Dk9ku72i.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-400-normal-DRC__1Mx.woff2",
    "revision": null
  }, {
    "url": "assets/instrument-sans-latin-400-normal-D1W7dsQl.woff",
    "revision": null
  }, {
    "url": "assets/index-_VPpBgOA.js",
    "revision": null
  }, {
    "url": "assets/index-CsoWwykr.css",
    "revision": null
  }, {
    "url": "assets/fraunces-vietnamese-full-normal-7BpKI3vF.woff2",
    "revision": null
  }, {
    "url": "assets/fraunces-latin-full-normal-CFFu7zhK.woff2",
    "revision": null
  }, {
    "url": "assets/fraunces-latin-ext-full-normal-YHFkNujK.woff2",
    "revision": null
  }, {
    "url": "assets/aura_workout_energy_1794576939868-DTDPyiaj.jpg",
    "revision": null
  }, {
    "url": "assets/aura_preset_silk_1794580615374-DzCLwYpT.jpg",
    "revision": null
  }, {
    "url": "assets/aura_preset_glass_1794580614896-QjsK0a6w.jpg",
    "revision": null
  }, {
    "url": "assets/aura_preset_chrome_1794580616078-DbJKHbsN.jpg",
    "revision": null
  }, {
    "url": "assets/aura_interest_motion_1794576940508-DXg2nAoR.jpg",
    "revision": null
  }, {
    "url": "assets/aura_interest_design_1794576939568-ilv2W5LR.jpg",
    "revision": null
  }, {
    "url": "assets/aura_interest_cinema_1794576939974--Oyk1sQ0.jpg",
    "revision": null
  }, {
    "url": "assets/aistudio/image_fed35e6d.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_fc12f0b7.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_e0e9b7dc.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_dfcc4d64.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_c786d243.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_b6a0fa0f.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_8ffccffa.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_6002cd4b.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_5120e0ad.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_3ae57fa9.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_19fa41bf.png",
    "revision": null
  }, {
    "url": "assets/aistudio/image_13a3b925.png",
    "revision": null
  }, {
    "url": "apple-touch-icon.png",
    "revision": "546982f080ce639630d660df874df74f"
  }, {
    "url": "icon.svg",
    "revision": "9606b183782283b180f6dd4b730a6073"
  }, {
    "url": "pwa-192x192.png",
    "revision": "a15eccf68f45a1323a0b1b76f0838aa5"
  }, {
    "url": "pwa-512x512.png",
    "revision": "b6f619f31b8b8b2bcc3254278ed0a513"
  }, {
    "url": "pwa-maskable-512x512.png",
    "revision": "dc55673e3f8847d842950e5714b26404"
  }, {
    "url": "manifest.webmanifest",
    "revision": "1b0c855a0f1db68ab5965ce542e90d24"
  }], {});
  workbox.cleanupOutdatedCaches();
  workbox.registerRoute(new workbox.NavigationRoute(workbox.createHandlerBoundToURL("index.html"), {
    denylist: [/^\/api\//]
  }));
  workbox.registerRoute(/^https:\/\/fonts\.googleapis\.com\/.*/i, new workbox.CacheFirst({
    "cacheName": "google-fonts-cache",
    plugins: [new workbox.ExpirationPlugin({
      maxEntries: 10,
      maxAgeSeconds: 31536000
    }), new workbox.CacheableResponsePlugin({
      statuses: [0, 200]
    })]
  }), 'GET');
  workbox.registerRoute(/^https:\/\/fonts\.gstatic\.com\/.*/i, new workbox.CacheFirst({
    "cacheName": "gstatic-fonts-cache",
    plugins: [new workbox.ExpirationPlugin({
      maxEntries: 10,
      maxAgeSeconds: 31536000
    }), new workbox.CacheableResponsePlugin({
      statuses: [0, 200]
    })]
  }), 'GET');

}));
