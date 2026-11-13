# Historical implementation

This module preserves all original Java sources and resources from the repository baseline for audit and selective reimplementation. It is compiled but is not a runtime dependency of the new platform. Its plugin descriptor is excluded from its JAR, so it cannot accidentally be installed as a second active game plugin.

See [the repository audit](../docs/REPOSITORY_AUDIT.md) for existing behavior, defects and preservation decisions. Do not deploy this module or copy its synchronous profile persistence into the new gameplay services.
