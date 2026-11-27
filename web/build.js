import { copyFile, mkdir, writeFile } from 'node:fs/promises';
import { isPublicKey } from './domain.js';

const base = process.env.PUBLIC_SUPABASE_URL;
const key = process.env.PUBLIC_SUPABASE_ANON_KEY;
let url;
try { url = new URL(base); } catch { throw new Error('Imposta PUBLIC_SUPABASE_URL.'); }
if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
  throw new Error('PUBLIC_SUPABASE_URL deve essere un URL HTTPS senza percorso.');
}
if (!isPublicKey(key)) throw new Error('Imposta PUBLIC_SUPABASE_ANON_KEY con una chiave pubblica.');
await mkdir('dist', { recursive: true });
for (const file of ['index.html', 'style.css', 'app.js', 'domain.js', 'review.js', 'review-domain.js']) await copyFile(file, `dist/${file}`);
await writeFile('dist/config.js', `export const supabaseUrl = ${JSON.stringify(url.origin)};\nexport const supabaseAnonKey = ${JSON.stringify(key)};\n`);
console.log('Web app preparata in web/dist.');
