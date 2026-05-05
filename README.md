# jZombies

This plugin now includes:

- Pre-generated 30-wave Zombies flow in `config.yml` (fully editable).
- Language file support via `language` + `lang/<code>.yml`.
- Optional MythicMobs spawning per-wave (`mythic:` field on mob entries).
- Weapon + skin system with custom model data and per-weapon skin restrictions.
- Persistent player skin ownership + equipped skins.
- Skill tree system with progression lock + reset-to-switch behavior.
- Skill points granted after each cleared round.
- Downed + revive system and barricade repair action with skill scaling.

## Commands

- `/jz start|stop|nextwave|reload` (admin)
- `/jz stats`
- `/jz weapons`
- `/jz buy <weaponId>`
- `/jz skin list <weaponId>`
- `/jz skin buy <skinId>`
- `/jz skin equip <weaponId> <skinId|default>`
- `/jz skills`
- `/jz skills tree <treeId>`
- `/jz skills upgrade <skillId>`
- `/jz skills reset`
