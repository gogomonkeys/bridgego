import {createRequire} from 'node:module';
import {dirname,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {writeFileSync} from 'node:fs';
const root=dirname(fileURLToPath(import.meta.url));
const frontend=resolve(root,'../../frontend');
const require=createRequire(resolve(frontend,'package.json'));
const {build}=await import(pathToFileURL(resolve(frontend,'node_modules/vite/dist/node/index.js')));
const react=(await import(pathToFileURL(require.resolve('@vitejs/plugin-react')))).default;
process.chdir(frontend);
await build({configFile:false,root,publicDir:resolve(frontend,'public'),base:'./',plugins:[react()],resolve:{alias:[{find:/^\.\/client$/,replacement:resolve(root,'client-stub.ts')},{find:/^\.\/WelcomeEntry$/,replacement:resolve(root,'entry-stub.tsx')},{find:/^\.\/TutorialFlow$/,replacement:resolve(root,'tutorial-stub.tsx')},{find:/^\.\/PwaStatus$/,replacement:resolve(root,'pwa-stub.tsx')},{find:'react',replacement:dirname(require.resolve('react/package.json'))},{find:'react-dom',replacement:dirname(require.resolve('react-dom/package.json'))}]},css:{postcss:frontend},build:{outDir:resolve(root,'dist'),emptyOutDir:true}});

writeFileSync(resolve(root,'dist/launcher.html'),`<!doctype html><meta charset="utf-8"><title>Feedback synthetic QA</title><style>body{margin:0;background:#ddd}iframe{display:block;border:0}</style><div id="view"></div><script>const p=new URLSearchParams(location.search);const f=document.createElement('iframe');f.width=p.get('width')||390;f.height=p.get('height')||844;f.src='./index.html?'+p;document.querySelector('#view').append(f);</script>`);
