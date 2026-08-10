'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assetDirectory = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const files = [
  'guard_rules.js',
  'instagram_guard.js',
  'youtube_guard.js',
  'facebook_guard.js'
];

for (const file of files) {
  const source = fs.readFileSync(path.join(assetDirectory, file), 'utf8')
    .replaceAll('__CLEARFEED_GUARD_VERSION__', '1')
    .replaceAll('__CLEARFEED_CSS_JSON__', '""')
    .replaceAll('__DIRECTONLY_GUARD_VERSION__', '1')
    .replaceAll('__DIRECTONLY_CSS_JSON__', '""')
    .replaceAll('__CLEARFEED_BRIDGE_TOKEN_JSON__', '"fixture-token"');
  new vm.Script(source, { filename: file });
  console.log(`Syntax OK: ${file}`);
}
