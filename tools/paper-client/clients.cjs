/* Two real offline-protocol clients, restricted to the isolated loopback playtest server. */
const mineflayer = require('mineflayer');
const readline = require('node:readline');
const bots = new Map();
const battling = new Set();
const busy = new Set();
const spamming = new Set();
function connect(name) {
  const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(process.argv[2]), username: name, auth: 'offline', version: '1.21.11'});
  bots.set(name, bot);
  bot.once('spawn', () => console.log('BOT_READY ' + name));
  bot.on('messagestr', message => console.log('BOT_MESSAGE ' + name + ' ' + message));
  bot.on('error', error => console.log('BOT_ERROR ' + name + ' ' + error.message));
  bot.on('kicked', reason => console.log('BOT_KICKED ' + name + ' ' + JSON.stringify(reason)));
  bot.on('end', () => { battling.delete(name); console.log('BOT_ENDED ' + name); });
}
connect('SurvivorOne');
connect('SurvivorTwo');
readline.createInterface({input: process.stdin}).on('line', line => {
  try {
    const command = JSON.parse(line);
    const bot = bots.get(command.name);
    if (command.op === 'connect') return connect(command.name);
    if (!bot) throw new Error('Unknown client');
    if (command.op === 'chat') bot.chat(command.text);
    if (command.op === 'swap') bot._client.write('block_dig', {status: 6, location: {x: 0, y: 0, z: 0}, face: 0, sequence: 0});
    if (command.op === 'slot') bot.setQuickBarSlot(command.slot);
    if (command.op === 'spam') { if(command.enabled)spamming.add(command.name);else {spamming.delete(command.name);bot.deactivateItem();} }
    if (command.op === 'hold') {if(command.enabled)bot.activateItem();else bot.deactivateItem();}
    if (command.op === 'battle') { if (command.enabled) battling.add(command.name); else battling.delete(command.name); }
    if (command.op === 'sneak') bot.setControlState('sneak', command.enabled);
    if (command.op === 'quit') bot.quit();
    if (command.op === 'exit') { for (const b of bots.values()) b.quit(); setTimeout(() => process.exit(0), 100); }
  } catch (error) { console.log('BOT_ERROR command ' + error.message); }
});
setInterval(async () => {
  for (const name of battling) {
    const bot = bots.get(name);
    if (!bot?.entity || busy.has(name)) continue;
    const target = Object.values(bot.entities).filter(e => e.name === 'zombie' && e.position.distanceTo(bot.entity.position) < 75)
      .sort((a,b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0];
    if (!target) continue;
    busy.add(name);
    try { await bot.lookAt(target.position.offset(0, 1.7, 0), true); bot.deactivateItem(); bot.activateItem(); }
    catch (error) { console.log('BOT_ERROR battle ' + error.message); }
    finally { busy.delete(name); }
  }
}, 120);
setInterval(()=>{for(const name of spamming){const b=bots.get(name);if(b?.entity){b.deactivateItem();b.activateItem();}}},25);
