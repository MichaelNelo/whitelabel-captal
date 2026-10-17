// The paused page's entry. See `upload.js` beside it for why the stub exists: the linker's public
// module exports `start` and nothing calls it, because one linker run yields one module
// initializer and the portal has it.
import { start } from './maintenance-app.js'

start()
