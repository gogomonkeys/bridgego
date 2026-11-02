import {build} from '../frontend/node_modules/esbuild/lib/main.js';
await build({entryPoints:['frontend/src/client.ts'],outfile:'qa/client-under-test.mjs',format:'esm',bundle:true,platform:'node',alias:{react:process.cwd()+'/qa/react-observer.mjs'}});
