export interface BunSystemCaBootstrapContext {
  platform: NodeJS.Platform
  bunVersion?: string
  nodeUseSystemCa?: string
}

/** Bun reads NODE_USE_SYSTEM_CA only during process startup. */
export const shouldRelaunchBunWithWindowsSystemCa = ({
  platform,
  bunVersion,
  nodeUseSystemCa
}: BunSystemCaBootstrapContext): boolean =>
  platform === 'win32' && typeof bunVersion === 'string' && bunVersion.length > 0 && nodeUseSystemCa !== '1'
