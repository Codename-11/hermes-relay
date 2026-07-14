import { execFileSync } from 'node:child_process'
import { userInfo } from 'node:os'

export type ProcessPrivilege = 'user' | 'administrator'

export interface ProcessIdentity {
  username: string
  privilege: ProcessPrivilege
}

export function windowsIntegrityIsElevated(groups: string): boolean {
  return /S-1-16-(12288|16384)\b/i.test(groups)
}

export function currentProcessIdentity(): ProcessIdentity {
  const username = userInfo().username
  if (process.platform === 'win32') {
    try {
      const groups = execFileSync('whoami.exe', ['/groups', '/fo', 'csv', '/nh'], {
        encoding: 'utf8',
        windowsHide: true
      })
      return {
        username,
        privilege: windowsIntegrityIsElevated(groups) ? 'administrator' : 'user'
      }
    } catch {
      return { username, privilege: 'user' }
    }
  }

  const uid = typeof process.getuid === 'function' ? process.getuid() : undefined
  return { username, privilege: uid === 0 ? 'administrator' : 'user' }
}
